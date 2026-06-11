"""Web Push (VAPID) — browser/PWA notifications alongside the Telegram pushes.

Configured by three env vars; without them everything degrades to a no-op and
the frontend hides the subscribe toggle:

- VAPID_PUBLIC_KEY   — urlsafe-base64 uncompressed P-256 public key
- VAPID_PRIVATE_KEY  — urlsafe-base64 raw private value
- VAPID_SUBJECT      — contact URI, e.g. mailto:admin@savefood.kz

Generate a pair with:
    python -c "from py_vapid import Vapid02; v=Vapid02(); v.generate_keys(); \
print('public:', v.public_pem().decode()); print('private:', v.private_pem().decode())"
or the helper in README («Web Push»).

Delivery is fire-and-forget in a daemon thread: notification paths must never
wait for N browser endpoints. Gone subscriptions (HTTP 404/410) are deleted.
"""
import json
import logging
import os
import re
import threading

from pywebpush import WebPushException, webpush

from backend.database import get_db_cursor

VAPID_PUBLIC_KEY = os.getenv("VAPID_PUBLIC_KEY", "")
VAPID_PRIVATE_KEY = os.getenv("VAPID_PRIVATE_KEY", "")
VAPID_SUBJECT = os.getenv("VAPID_SUBJECT", "mailto:support@savefood.local")

_TAG_RE = re.compile(r"<[^>]+>")


def is_configured() -> bool:
    return bool(VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY)


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


def notify_role(role: str, related_id: int, text: str, url: str = "/"):
    """Push to the account bound to (role, related_id) — mirrors the Telegram
    notify_* helpers. No-op without VAPID keys or subscriptions."""
    if not is_configured() or not related_id:
        return
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "SELECT id FROM users WHERE role = %s AND related_id = %s",
                (role, related_id),
            )
            row = cur.fetchone()
        if not row:
            return
        body = strip_html(text)
        threading.Thread(
            target=_send_to_user_blocking,
            args=(row["id"], "SaveFood", body, url),
            daemon=True,
        ).start()
    except Exception as e:
        logging.warning("[push] notify_role failed: %s", e)
