import os
import logging
import httpx
from backend.database import get_db_cursor

TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
SITE_URL = os.getenv("SITE_URL", "http://localhost:3000")


def send_message(chat_id: str, text: str) -> bool:
    if not TELEGRAM_BOT_TOKEN or not chat_id:
        return False
    try:
        from backend.proxy_service import get_proxy_url
        proxy_url = get_proxy_url()
        proxies = {"all://": proxy_url} if proxy_url else None
        with httpx.Client(proxies=proxies, timeout=10) as client:
            resp = client.post(
                f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage",
                json={"chat_id": chat_id, "text": text, "parse_mode": "HTML"},
            )
        return resp.status_code == 200
    except Exception as e:
        logging.warning("Telegram send_message failed: %s", e)
        return False


def get_chat_id_by_related(role: str, related_id: int) -> str:
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "SELECT telegram_chat_id FROM users WHERE role = %s AND related_id = %s",
                (role, related_id),
            )
            row = cur.fetchone()
            return row["telegram_chat_id"] if row else None
    except Exception:
        return None


def _also_web_push(role: str, related_id: int, text: str):
    # Web Push mirrors every Telegram notification so users without the bot
    # still get pushes in the browser/PWA. Best-effort, never blocks.
    try:
        from backend import push_service
        push_service.notify_role(role, related_id, text)
    except Exception:
        pass


def notify_needy(needy_id: int, text: str):
    chat_id = get_chat_id_by_related("needy", needy_id)
    if chat_id:
        send_message(chat_id, text)
    _also_web_push("needy", needy_id, text)


def notify_shop(shop_id: int, text: str):
    chat_id = get_chat_id_by_related("shop", shop_id)
    if chat_id:
        send_message(chat_id, text)
    _also_web_push("shop", shop_id, text)


def notify_volunteer(volunteer_id: int, text: str):
    chat_id = get_chat_id_by_related("volunteer", volunteer_id)
    if chat_id:
        send_message(chat_id, text)
    _also_web_push("volunteer", volunteer_id, text)
