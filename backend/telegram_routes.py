import os
import asyncio
import secrets
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request

from backend.auth import get_current_user
from backend.database import get_db_cursor

router = APIRouter()

SITE_URL = os.getenv("SITE_URL", "http://localhost")
BOT_NAME = os.getenv("TELEGRAM_BOT_NAME", "savefood_bot")
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")

# polling=true by default; set TELEGRAM_POLLING=false + configure webhook in prod
USE_POLLING = os.getenv("TELEGRAM_POLLING", "true").lower() == "true"

_bot = None
_dp  = None
_polling_task: asyncio.Task = None


def _build_bot_and_dp():
    global _bot, _dp
    if _bot is not None:
        return _bot, _dp
    if not TELEGRAM_BOT_TOKEN:
        return None, None

    from aiogram import Bot, Dispatcher, Router as BotRouter, F
    from aiogram.types import Message
    from aiogram.filters import CommandStart, Command
    from aiogram.filters.command import CommandObject

    bot_router = BotRouter()

    @bot_router.message(CommandStart())
    async def handle_start(message: Message, command: CommandObject):
        chat_id = str(message.chat.id)
        args = command.args or ""

        if args.startswith("link_"):
            token = args[5:]
            with get_db_cursor() as cur:
                cur.execute(
                    """
                    SELECT * FROM telegram_link_tokens
                    WHERE token = %s
                      AND created_at >= NOW() - INTERVAL '10 minutes'
                    """,
                    (token,),
                )
                link_row = cur.fetchone()

            if not link_row:
                await message.answer(
                    "❌ Ссылка устарела или недействительна. "
                    "Создайте новую в настройках профиля."
                )
                return

            user_id = link_row["user_id"]
            with get_db_cursor() as cur:
                cur.execute(
                    "UPDATE users SET telegram_chat_id = %s WHERE id = %s",
                    (chat_id, user_id),
                )
                cur.execute(
                    "DELETE FROM telegram_link_tokens WHERE token = %s", (token,)
                )

            await message.answer(
                "✅ <b>Telegram успешно подключён</b> к вашему аккаунту SaveFood!\n\n"
                "Теперь вы будете получать уведомления о доставках прямо сюда.",
                parse_mode="HTML",
            )
        else:
            await message.answer(
                f"👋 Добро пожаловать в <b>SaveFood</b>!\n\n"
                f"Мы соединяем магазины, волонтёров и нуждающихся "
                f"в единую систему распределения еды.\n\n"
                f'🔗 <a href="{SITE_URL}">Открыть платформу</a>\n\n'
                f"Войдите в аккаунт и подключите Telegram в настройках профиля "
                f"для получения уведомлений.",
                parse_mode="HTML",
                disable_web_page_preview=True,
            )

    @bot_router.message(Command("chat"))
    async def handle_chat_command(message: Message):
        await message.answer(
            "💬 Просто напишите сообщение в этот чат — оно будет переслано волонтёру/получателю, "
            "если у вас есть активный маршрут.",
        )

    @bot_router.message(F.text & ~F.text.startswith('/'))
    async def handle_relay_message(message: Message):
        chat_id = str(message.chat.id)
        text = message.text or ""

        with get_db_cursor() as cur:
            cur.execute("SELECT id, role, related_id, username FROM users WHERE telegram_chat_id = %s", (chat_id,))
            sender = cur.fetchone()

        if not sender:
            await message.answer("❓ Ваш аккаунт не привязан к SaveFood. Используйте /start link_<token>.")
            return

        role = sender['role']
        related_id = sender['related_id']
        sender_name = sender['username']

        from backend import telegram_service as tgsvc

        if role == 'volunteer':
            with get_db_cursor() as cur:
                cur.execute(
                    "SELECT * FROM volunteer_routes WHERE volunteer_id = %s AND status = 'in_progress' ORDER BY started_at DESC LIMIT 1",
                    (related_id,),
                )
                route = cur.fetchone()
            if not route:
                await message.answer("У вас нет активного маршрута.")
                return
            import json as _json
            points = _json.loads(route.get('points') or '[]')
            needy_ids = []
            for p in points:
                if p.get('kind') == 'ticket' and not p.get('done') and p.get('ticket_id'):
                    with get_db_cursor() as cur:
                        cur.execute("SELECT needy_id FROM tickets WHERE id = %s", (p['ticket_id'],))
                        t = cur.fetchone()
                        if t:
                            needy_ids.append(t['needy_id'])
            if not needy_ids:
                await message.answer("Нет активных получателей для пересылки.")
                return
            for nid in set(needy_ids):
                tgsvc.notify_needy(nid, f"💬 Волонтёр {sender_name}: {text}")
            await message.answer("✅ Сообщение отправлено")

        elif role == 'needy':
            with get_db_cursor() as cur:
                cur.execute(
                    "SELECT assigned_volunteer_id FROM tickets WHERE needy_id = %s AND status = 'assigned' LIMIT 1",
                    (related_id,),
                )
                ticket = cur.fetchone()
            if not ticket or not ticket['assigned_volunteer_id']:
                await message.answer("У вас нет активного назначенного волонтёра.")
                return
            tgsvc.notify_volunteer(ticket['assigned_volunteer_id'], f"💬 Получатель {sender_name}: {text}")
            await message.answer("✅ Сообщение отправлено")

        else:
            await message.answer("Пересылка сообщений доступна только волонтёрам и получателям.")

    _bot = Bot(token=TELEGRAM_BOT_TOKEN)
    _dp  = Dispatcher()
    _dp.include_router(bot_router)
    return _bot, _dp


async def start_polling():
    """Start long-polling in a background asyncio task."""
    global _polling_task
    if not USE_POLLING or not TELEGRAM_BOT_TOKEN:
        return
    bot, dp = _build_bot_and_dp()
    if bot is None:
        return

    async def _run():
        try:
            logging.info("[telegram] Starting polling for @%s", BOT_NAME)
            await dp.start_polling(bot, handle_signals=False)
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logging.error("[telegram] Polling error: %s", e)

    _polling_task = asyncio.create_task(_run())


async def stop_polling():
    """Cancel the polling task on shutdown."""
    global _polling_task
    if _polling_task and not _polling_task.done():
        _polling_task.cancel()
        try:
            await _polling_task
        except asyncio.CancelledError:
            pass
    if _bot:
        await _bot.session.close()
    logging.info("[telegram] Polling stopped")


# --- Webhook endpoint (used in production with a public HTTPS URL) ---

@router.post("/telegram/webhook")
async def telegram_webhook(request: Request):
    if USE_POLLING:
        return {"ok": True}   # ignore — polling is active
    bot, dp = _build_bot_and_dp()
    if bot is None:
        return {"ok": True}
    try:
        from aiogram.types import Update
        body = await request.json()
        update = Update.model_validate(body)
        await dp.feed_update(bot=bot, update=update)
    except Exception as e:
        logging.warning("[telegram] Webhook error: %s", e)
    return {"ok": True}


# --- Init-link for authenticated users ---

@router.get("/auth/telegram/init-link")
def init_telegram_link(current_user: dict = Depends(get_current_user)):
    username = current_user.get("sub")
    if not username:
        raise HTTPException(status_code=401, detail="Invalid token")

    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, telegram_chat_id FROM users WHERE username = %s", (username,)
        )
        row = cur.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="User not found")
        user_id = row["id"]
        already_linked = bool(row["telegram_chat_id"])

    token = secrets.token_urlsafe(24)
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM telegram_link_tokens WHERE user_id = %s", (user_id,))
        cur.execute(
            "INSERT INTO telegram_link_tokens (token, user_id, created_at) VALUES (%s, %s, %s)",
            (token, user_id, datetime.now(timezone.utc)),
        )

    return {
        "link": f"https://t.me/{BOT_NAME}?start=link_{token}",
        "bot_name": BOT_NAME,
        "already_linked": already_linked,
        "expires_in": 600,
    }
