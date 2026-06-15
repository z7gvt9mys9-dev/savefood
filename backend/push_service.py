"""Web Push (VAPID) + Firebase Cloud Messaging (FCM) — browser/PWA and native
Android notifications alongside the Telegram pushes.

Web Push is configured by three env vars; without them that channel degrades to
a no-op and the frontend hides the subscribe toggle:

- VAPID_PUBLIC_KEY   — urlsafe-base64 uncompressed P-256 public key
- VAPID_PRIVATE_KEY  — urlsafe-base64 raw private value
- VAPID_SUBJECT      — contact URI, e.g. mailto:admin@savefood.kz

Generate a pair with:
    python -c "from py_vapid import Vapid02; v=Vapid02(); v.generate_keys(); \
print('public:', v.public_pem().decode()); print('private:', v.private_pem().decode())"
or the helper in README («Web Push»).

FCM (the native Android channel) is an independent, additive path gated behind
FCM_ENABLED (off by default). It needs a Firebase service account; credentials
come only from env, never hardcoded:

- FCM_ENABLED          — "true" to turn the channel on
- FCM_PROJECT_ID       — Firebase project id (target of the v1 send URL)
- FCM_CREDENTIALS_JSON — service account JSON inline (preferred in containers), OR
- FCM_CREDENTIALS_FILE — path to the service account JSON file

Both channels are independent: each is skipped unless its own config is present,
so enabling FCM never affects Web Push and vice versa.

Delivery is fire-and-forget in a daemon thread: notification paths must never
wait for N browser endpoints. Gone subscriptions (HTTP 404/410) and stale FCM
tokens (404/UNREGISTERED) are deleted.
"""
import json
import logging
import os
import re
import threading

import httpx
from pywebpush import WebPushException, webpush

from backend.database import get_db_cursor

VAPID_PUBLIC_KEY = os.getenv("VAPID_PUBLIC_KEY", "")
VAPID_PRIVATE_KEY = os.getenv("VAPID_PRIVATE_KEY", "")
VAPID_SUBJECT = os.getenv("VAPID_SUBJECT", "mailto:support@savefood.local")

FCM_ENABLED = os.getenv("FCM_ENABLED", "false").strip().lower() in ("1", "true", "yes", "on")
FCM_PROJECT_ID = os.getenv("FCM_PROJECT_ID", "")
FCM_CREDENTIALS_JSON = os.getenv("FCM_CREDENTIALS_JSON", "")
FCM_CREDENTIALS_FILE = os.getenv("FCM_CREDENTIALS_FILE", "")
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

_TAG_RE = re.compile(r"<[^>]+>")

# Cached service-account credentials object (google-auth refreshes its own token
# in place). Guarded because notify_role spawns a thread per notification.
_fcm_creds = None
_fcm_creds_lock = threading.Lock()


def is_configured() -> bool:
    return bool(VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY)


def fcm_is_configured() -> bool:
    return bool(FCM_ENABLED and FCM_PROJECT_ID and (FCM_CREDENTIALS_JSON or FCM_CREDENTIALS_FILE))


def strip_html(text: str) -> str:
    """Telegram messages carry HTML (<b>…</b>); browser notifications are plain."""
    return _TAG_RE.sub("", text or "")


def save_subscription(user_id: int, endpoint: str, p256dh: str, auth: str):
    with get_db_cursor() as cur:
        # The same browser may re-subscribe after a permission reset — the
        # endpoint stays unique, ownership follows the current login.
        cur.execute(
            """
            INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (endpoint) DO UPDATE
                SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth
            """,
            (user_id, endpoint, p256dh, auth),
        )


def delete_subscription(user_id: int, endpoint: str):
    with get_db_cursor() as cur:
        cur.execute(
            "DELETE FROM push_subscriptions WHERE user_id = %s AND endpoint = %s",
            (user_id, endpoint),
        )


def _drop_endpoint(endpoint: str):
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM push_subscriptions WHERE endpoint = %s", (endpoint,))


# ── FCM token storage ────────────────────────────────────────────────────────

def save_fcm_token(user_id: int, token: str, role: str, related_id):
    with get_db_cursor() as cur:
        # The same device may re-register after a reinstall or after a different
        # account logs in on it — the token stays unique, ownership and the
        # dispatch key follow the current login (mirrors save_subscription).
        cur.execute(
            """
            INSERT INTO fcm_tokens (user_id, token, role, related_id)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (token) DO UPDATE
                SET user_id = EXCLUDED.user_id, role = EXCLUDED.role,
                    related_id = EXCLUDED.related_id
            """,
            (user_id, token, role, related_id),
        )


def delete_fcm_token(user_id: int, token: str):
    with get_db_cursor() as cur:
        cur.execute(
            "DELETE FROM fcm_tokens WHERE user_id = %s AND token = %s",
            (user_id, token),
        )


def _drop_fcm_token(token: str):
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM fcm_tokens WHERE token = %s", (token,))


def _send_to_user_blocking(user_id: int, title: str, body: str, url: str):
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = %s",
            (user_id,),
        )
        subs = cur.fetchall()
    payload = json.dumps({"title": title, "body": body, "url": url}, ensure_ascii=False)
    for sub in subs:
        try:
            webpush(
                subscription_info={
                    "endpoint": sub["endpoint"],
                    "keys": {"p256dh": sub["p256dh"], "auth": sub["auth"]},
                },
                data=payload,
                vapid_private_key=VAPID_PRIVATE_KEY,
                vapid_claims={"sub": VAPID_SUBJECT},
                timeout=10,
            )
        except WebPushException as e:
            status = getattr(e.response, "status_code", None)
            if status in (404, 410):
                _drop_endpoint(sub["endpoint"])  # browser unsubscribed/expired
            else:
                logging.warning("[push] send failed (%s): %s", status, e)
        except Exception as e:
            logging.warning("[push] send failed: %s", e)


# ── FCM (Firebase Cloud Messaging, HTTP v1) ──────────────────────────────────

def _fcm_credentials_info() -> dict:
    """Service account dict from FCM_CREDENTIALS_JSON (inline) or _FILE (path)."""
    if FCM_CREDENTIALS_JSON:
        return json.loads(FCM_CREDENTIALS_JSON)
    with open(FCM_CREDENTIALS_FILE, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _fcm_access_token() -> str:
    """OAuth2 bearer for FCM v1. google-auth caches/refreshes the token on the
    credentials object; we just hold that object and refresh when it's stale.
    Imported lazily so the module loads even where google-auth isn't installed
    (FCM off)."""
    global _fcm_creds
    from google.oauth2 import service_account
    import google.auth.transport.requests

    with _fcm_creds_lock:
        if _fcm_creds is None:
            _fcm_creds = service_account.Credentials.from_service_account_info(
                _fcm_credentials_info(), scopes=[FCM_SCOPE]
            )
        if not _fcm_creds.valid:
            _fcm_creds.refresh(google.auth.transport.requests.Request())
        return _fcm_creds.token


def _send_fcm_to_role_blocking(role: str, related_id: int, title: str, body: str, url: str):
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT token FROM fcm_tokens WHERE role = %s AND related_id = %s",
            (role, related_id),
        )
        rows = cur.fetchall()
    if not rows:
        return
    try:
        access_token = _fcm_access_token()
    except Exception as e:
        logging.warning("[fcm] could not obtain access token: %s", e)
        return
    endpoint = f"https://fcm.googleapis.com/v1/projects/{FCM_PROJECT_ID}/messages:send"
    headers = {"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"}
    for r in rows:
        message = {
            "message": {
                "token": r["token"],
                "notification": {"title": title, "body": body},
                # data is string-only in FCM; the app routes on the deep-link url.
                "data": {"url": url},
            }
        }
        try:
            resp = httpx.post(endpoint, headers=headers, json=message, timeout=10)
            if resp.status_code == 200:
                continue
            # 404 UNREGISTERED / 400 INVALID_ARGUMENT on the token ⇒ the device is
            # gone or the token is malformed; prune it like a dead Web Push endpoint.
            if resp.status_code == 404 or "UNREGISTERED" in resp.text:
                _drop_fcm_token(r["token"])
            else:
                logging.warning("[fcm] send failed (%s): %s", resp.status_code, resp.text)
        except Exception as e:
            logging.warning("[fcm] send failed: %s", e)


def notify_role(role: str, related_id: int, text: str, url: str = "/"):
    """Push to the account bound to (role, related_id) — mirrors the Telegram
    notify_* helpers. Dispatches to every configured channel (Web Push and/or
    FCM); each is independently a no-op without its own config or recipients."""
    if not related_id:
        return
    body = strip_html(text)

    if is_configured():
        try:
            with get_db_cursor() as cur:
                cur.execute(
                    "SELECT id FROM users WHERE role = %s AND related_id = %s",
                    (role, related_id),
                )
                row = cur.fetchone()
            if row:
                threading.Thread(
                    target=_send_to_user_blocking,
                    args=(row["id"], "SaveFood", body, url),
                    daemon=True,
                ).start()
        except Exception as e:
            logging.warning("[push] notify_role failed: %s", e)

    if fcm_is_configured():
        try:
            threading.Thread(
                target=_send_fcm_to_role_blocking,
                args=(role, related_id, "SaveFood", body, url),
                daemon=True,
            ).start()
        except Exception as e:
            logging.warning("[fcm] notify_role failed: %s", e)
