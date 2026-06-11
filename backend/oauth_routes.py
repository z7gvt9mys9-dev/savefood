"""Social auth: link Google / Yandex / Telegram to an account + login through them.

Google and Yandex use the standard server-side authorization-code flow:
  frontend → GET /auth/oauth/{provider}/start  → provider consent screen
  provider → GET /auth/oauth/{provider}/callback → link or login, redirect back.
The `state` parameter is a short-lived signed JWT, so the callback can trust
the mode (login/link) and the user id without server-side session storage.

Telegram deliberately does NOT use the Login Widget: the widget requires a
domain registered via BotFather (/setdomain), which breaks behind throwaway
tunnel URLs. Instead we reuse the bot deep-link mechanism:
  POST /auth/telegram/login/start → token + t.me/<bot>?start=login_<token>
  user opens the bot, bot fills user_id for the token (telegram_routes.py)
  frontend polls POST /auth/telegram/login/poll until it gets a JWT.
"""
import os
import secrets
import logging
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode, quote

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse
from jose import jwt, JWTError
from pydantic import BaseModel

from backend.auth import SECRET_KEY, ALGORITHM, create_access_token, get_current_user, decode_access_token
from backend.database import get_db_cursor
from backend.limiter import limiter

router = APIRouter(prefix="/auth", tags=["oauth"])

GOOGLE_CLIENT_ID = os.getenv("GOOGLE_CLIENT_ID", "")
GOOGLE_CLIENT_SECRET = os.getenv("GOOGLE_CLIENT_SECRET", "")
YANDEX_CLIENT_ID = os.getenv("YANDEX_CLIENT_ID", "")
YANDEX_CLIENT_SECRET = os.getenv("YANDEX_CLIENT_SECRET", "")
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
BOT_NAME = os.getenv("TELEGRAM_BOT_NAME", "savefood_bot")

# Base public URL the user's browser can reach — used both for provider
# redirect_uri (must match the URL registered in the provider console) and
# for redirecting back to the SPA after the callback.
PUBLIC_URL = (os.getenv("OAUTH_PUBLIC_URL") or os.getenv("SITE_URL") or "http://localhost:3000").rstrip("/")

STATE_TTL_MINUTES = 10
LOGIN_TOKEN_TTL_MINUTES = 10

PROVIDERS = {
    "google": {
        "auth_url": "https://accounts.google.com/o/oauth2/v2/auth",
        "token_url": "https://oauth2.googleapis.com/token",
        "userinfo_url": "https://openidconnect.googleapis.com/v1/userinfo",
        "scope": "openid email",
        "column": "google_id",
    },
    "yandex": {
        "auth_url": "https://oauth.yandex.ru/authorize",
        "token_url": "https://oauth.yandex.ru/token",
        "userinfo_url": "https://login.yandex.ru/info?format=json",
        "scope": None,  # Yandex uses the permissions configured on the app
        "column": "yandex_id",
    },
}


def _credentials(provider: str):
    if provider == "google":
        return GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
    if provider == "yandex":
        return YANDEX_CLIENT_ID, YANDEX_CLIENT_SECRET
    return "", ""


def _require_provider(provider: str) -> dict:
    cfg = PROVIDERS.get(provider)
    if not cfg:
        raise HTTPException(status_code=404, detail="Unknown provider")
    cid, csecret = _credentials(provider)
    if not cid or not csecret:
        raise HTTPException(status_code=503, detail=f"Вход через {provider} не настроен на сервере")
    return cfg


def _redirect_uri(provider: str) -> str:
    return f"{PUBLIC_URL}/auth/oauth/{provider}/callback"


def _make_state(payload: dict) -> str:
    data = dict(payload)
    data["purpose"] = "oauth_state"
    data["exp"] = datetime.now(timezone.utc) + timedelta(minutes=STATE_TTL_MINUTES)
    return jwt.encode(data, SECRET_KEY, algorithm=ALGORITHM)


def _read_state(state: str) -> dict | None:
    try:
        data = jwt.decode(state, SECRET_KEY, algorithms=[ALGORITHM])
    except JWTError:
        return None
    if data.get("purpose") != "oauth_state":
        return None
    return data


def _front_error(message: str) -> RedirectResponse:
    return RedirectResponse(f"{PUBLIC_URL}/auth#oauth_error={quote(message)}")


def _fetch_provider_identity(provider: str, cfg: dict, code: str) -> tuple[str, str | None]:
    """Exchange the code and return (provider_user_id, email-or-login)."""
    cid, csecret = _credentials(provider)
    data = {
        "grant_type": "authorization_code",
        "code": code,
        "client_id": cid,
        "client_secret": csecret,
        "redirect_uri": _redirect_uri(provider),
    }
    with httpx.Client(timeout=15) as client:
        tok = client.post(cfg["token_url"], data=data)
        if tok.status_code != 200:
            logging.warning("[oauth] %s token exchange %s: %s", provider, tok.status_code, tok.text[:200])
            raise ValueError("token_exchange_failed")
        access_token = tok.json().get("access_token")
        if not access_token:
            raise ValueError("no_access_token")

        if provider == "google":
            info = client.get(cfg["userinfo_url"], headers={"Authorization": f"Bearer {access_token}"})
            if info.status_code != 200:
                raise ValueError("userinfo_failed")
            j = info.json()
            return str(j["sub"]), j.get("email")
        else:  # yandex
            info = client.get(cfg["userinfo_url"], headers={"Authorization": f"OAuth {access_token}"})
            if info.status_code != 200:
                raise ValueError("userinfo_failed")
            j = info.json()
            return str(j["id"]), j.get("default_email") or j.get("login")


# ── Provider discovery (frontend hides unconfigured buttons) ────────────────

@router.get("/oauth/providers")
def list_providers():
    return {
        "google": bool(GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET),
        "yandex": bool(YANDEX_CLIENT_ID and YANDEX_CLIENT_SECRET),
        "telegram": bool(TELEGRAM_BOT_TOKEN),
    }


# ── OAuth start / callback (Google, Yandex) ─────────────────────────────────

@router.get("/oauth/{provider}/start")
@limiter.limit("10/minute")
def oauth_start(provider: str, request: Request, mode: str = "login", next: str = "/"):
    cfg = _require_provider(provider)
    if mode not in ("login", "link"):
        raise HTTPException(status_code=400, detail="mode must be 'login' or 'link'")

    state_payload = {"p": provider, "m": mode, "nxt": next if next.startswith("/") else "/"}

    if mode == "link":
        # Linking attaches the provider id to the CURRENT account — require a
        # valid bearer token and pin the db user id inside the signed state.
        auth_header = request.headers.get("authorization") or ""
        token = auth_header[7:] if auth_header.lower().startswith("bearer ") else None
        payload = decode_access_token(token) if token else None
        if not payload:
            raise HTTPException(status_code=401, detail="Авторизуйтесь, чтобы привязать аккаунт")
        with get_db_cursor() as cur:
            cur.execute("SELECT id FROM users WHERE username = %s", (payload.get("sub"),))
            row = cur.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="User not found")
        state_payload["uid"] = row["id"]

    cid, _ = _credentials(provider)
    params = {
        "response_type": "code",
        "client_id": cid,
        "redirect_uri": _redirect_uri(provider),
        "state": _make_state(state_payload),
    }
    if cfg["scope"]:
        params["scope"] = cfg["scope"]
    return {"url": f"{cfg['auth_url']}?{urlencode(params)}"}


@router.get("/oauth/{provider}/callback")
def oauth_callback(provider: str, request: Request, code: str = None, state: str = None, error: str = None):
    cfg = PROVIDERS.get(provider)
    if not cfg:
        return _front_error("Неизвестный провайдер")
    if error:
        return _front_error("Вход отменён на стороне провайдера")
    st = _read_state(state or "")
    if not st or st.get("p") != provider:
        return _front_error("Сессия входа устарела — попробуйте ещё раз")
    if not code:
        return _front_error("Провайдер не вернул код авторизации")

    try:
        provider_uid, email = _fetch_provider_identity(provider, cfg, code)
    except Exception:
        return _front_error("Не удалось получить данные у провайдера — попробуйте ещё раз")

    column = cfg["column"]

    if st.get("m") == "link":
        uid = st.get("uid")
        with get_db_cursor() as cur:
            cur.execute(f"SELECT id FROM users WHERE {column} = %s AND id != %s", (provider_uid, uid))
            if cur.fetchone():
                return _front_error("Этот аккаунт уже привязан к другому пользователю SaveFood")
            cur.execute(f"UPDATE users SET {column} = %s WHERE id = %s", (provider_uid, uid))
        nxt = st.get("nxt") or "/"
        return RedirectResponse(f"{PUBLIC_URL}{nxt}#linked={provider}")

    # mode == login
    with get_db_cursor() as cur:
        cur.execute(f"SELECT * FROM users WHERE {column} = %s", (provider_uid,))
        user = cur.fetchone()
    if not user:
        return _front_error("Аккаунт не найден. Войдите по паролю и привяжите " + provider + " в профиле")
    if user.get("is_blocked"):
        return _front_error("Аккаунт заблокирован администратором")

    access_token = create_access_token(
        data={"sub": user["username"], "role": user["role"], "related_id": user["related_id"]}
    )
    # The token travels in the URL fragment: fragments are not sent to servers
    # and never appear in access logs.
    frag = urlencode({
        "oauth_token": access_token,
        "role": user["role"],
        "related_id": user["related_id"] if user["related_id"] is not None else "",
    })
    return RedirectResponse(f"{PUBLIC_URL}/auth#{frag}")


# ── Link status / unlink (profile UI) ───────────────────────────────────────

@router.get("/links")
def link_status(current_user: dict = Depends(get_current_user)):
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT telegram_chat_id, google_id, yandex_id FROM users WHERE username = %s",
            (current_user.get("sub"),),
        )
        row = cur.fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="User not found")
    return {
        "telegram": bool(row["telegram_chat_id"]),
        "google": bool(row["google_id"]),
        "yandex": bool(row["yandex_id"]),
    }


@router.post("/links/{provider}/unlink")
def unlink(provider: str, current_user: dict = Depends(get_current_user)):
    columns = {"telegram": "telegram_chat_id", "google": "google_id", "yandex": "yandex_id"}
    column = columns.get(provider)
    if not column:
        raise HTTPException(status_code=404, detail="Unknown provider")
    with get_db_cursor() as cur:
        cur.execute(f"UPDATE users SET {column} = NULL WHERE username = %s", (current_user.get("sub"),))
    return {"ok": True}


# ── Telegram login (deep-link + polling, no Login Widget) ───────────────────

class TelegramPoll(BaseModel):
    token: str


@router.post("/telegram/login/start")
@limiter.limit("10/minute")
def telegram_login_start(request: Request):
    if not TELEGRAM_BOT_TOKEN:
        raise HTTPException(status_code=503, detail="Вход через Telegram не настроен на сервере")
    token = secrets.token_urlsafe(24)
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO telegram_login_tokens (token, created_at) VALUES (%s, %s)",
            (token, datetime.now(timezone.utc)),
        )
        # opportunistic cleanup of expired rows
        cur.execute(
            "DELETE FROM telegram_login_tokens WHERE created_at < NOW() - INTERVAL '%s minutes'",
            (LOGIN_TOKEN_TTL_MINUTES * 6,),
        )
    return {
        "token": token,
        "link": f"https://t.me/{BOT_NAME}?start=login_{token}",
        "expires_in": LOGIN_TOKEN_TTL_MINUTES * 60,
    }


@router.post("/telegram/login/poll")
@limiter.limit("60/minute")
def telegram_login_poll(request: Request, payload: TelegramPoll):
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM telegram_login_tokens WHERE token = %s AND created_at >= NOW() - INTERVAL '%s minutes'",
            (payload.token, LOGIN_TOKEN_TTL_MINUTES),
        )
        row = cur.fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Ссылка устарела — начните вход заново")
    if not row.get("user_id"):
        return {"status": "pending"}

    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM users WHERE id = %s", (row["user_id"],))
        user = cur.fetchone()
        cur.execute("DELETE FROM telegram_login_tokens WHERE token = %s", (payload.token,))
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    if user.get("is_blocked"):
        raise HTTPException(status_code=403, detail="Аккаунт заблокирован администратором")

    access_token = create_access_token(
        data={"sub": user["username"], "role": user["role"], "related_id": user["related_id"]}
    )
    return {
        "status": "ok",
        "access_token": access_token,
        "token_type": "bearer",
        "role": user["role"],
        "related_id": user["related_id"],
    }
