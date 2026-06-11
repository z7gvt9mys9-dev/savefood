import os
import asyncio
import html
import secrets
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request

from backend.auth import get_current_user
from backend.database import get_db_cursor
from backend.limiter import limiter

router = APIRouter()

SITE_URL = os.getenv("SITE_URL", "http://localhost")
BOT_NAME = os.getenv("TELEGRAM_BOT_NAME", "savefood_bot")
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_WEBHOOK_SECRET = os.getenv("TELEGRAM_WEBHOOK_SECRET", "")

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
        elif args.startswith("login_"):
            # Login-via-Telegram: confirm the pending browser session if this
            # chat is already linked to a SaveFood account.
            token = args[6:]
            with get_db_cursor() as cur:
                cur.execute("SELECT id, username FROM users WHERE telegram_chat_id = %s", (chat_id,))
                user = cur.fetchone()
            if not user:
                await message.answer(
                    "❌ Этот Telegram не привязан ни к одному аккаунту SaveFood.\n"
                    "Войдите на платформу по паролю и нажмите «Подключить Telegram» в профиле — "
                    "после этого вход через Telegram заработает."
                )
                return
            with get_db_cursor() as cur:
                cur.execute(
                    """
                    UPDATE telegram_login_tokens SET user_id = %s
                    WHERE token = %s AND user_id IS NULL
                      AND created_at >= NOW() - INTERVAL '10 minutes'
                    """,
                    (user["id"], token),
                )
                confirmed = cur.rowcount > 0
            if confirmed:
                await message.answer(
                    f"✅ Вход подтверждён для аккаунта <b>{html.escape(user['username'])}</b>.\n"
                    "Вернитесь на вкладку с сайтом — вы уже вошли.",
                    parse_mode="HTML",
                )
            else:
                await message.answer("❌ Ссылка для входа устарела или уже использована. Начните вход заново.")
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

    HELP_TEXT = (
        "📋 <b>Команды SaveFood-бота</b>\n\n"
        "/start — добро пожаловать / привязать аккаунт\n"
        "/help — это сообщение\n"
        "/status — проверить, привязан ли аккаунт и текущий статус\n"
        "/chat — как переписываться с волонтёром или получателем\n"
        "/unlink — отвязать Telegram от аккаунта SaveFood\n\n"
        "💬 Просто отправьте текст — он будет переслан вашему волонтёру/получателю "
        "при наличии активного маршрута.\n"
        "🤖 Если активной доставки нет — на вопрос ответит ИИ-помощник, "
        "а сложные вопросы он передаст администратору."
    )

    @bot_router.message(Command("help"))
    async def handle_help(message: Message):
        await message.answer(HELP_TEXT, parse_mode="HTML")

    @bot_router.message(Command("status"))
    async def handle_status(message: Message):
        chat_id = str(message.chat.id)
        with get_db_cursor() as cur:
            cur.execute(
                "SELECT id, username, role, related_id FROM users WHERE telegram_chat_id = %s",
                (chat_id,),
            )
            user = cur.fetchone()

        if not user:
            await message.answer(
                "❌ Аккаунт <b>не привязан</b>.\n\n"
                f'Войдите на <a href="{SITE_URL}">платформу</a> и подключите Telegram в настройках профиля.',
                parse_mode="HTML",
                disable_web_page_preview=True,
            )
            return

        role_labels = {"shop": "Магазин", "volunteer": "Волонтёр", "needy": "Получатель", "admin": "Администратор"}
        role_label = role_labels.get(user["role"], user["role"])
        lines = [
            f"✅ Аккаунт привязан",
            f"👤 {user['username']} · {role_label}",
        ]

        role = user["role"]
        related_id = user["related_id"]
        with get_db_cursor() as cur:
            if role == "volunteer":
                cur.execute(
                    "SELECT id, status, started_at FROM volunteer_routes "
                    "WHERE volunteer_id = %s AND status = 'in_progress' ORDER BY started_at DESC LIMIT 1",
                    (related_id,),
                )
                route = cur.fetchone()
                if route:
                    lines.append(f"🚗 Активный маршрут #{route['id']}")
                else:
                    lines.append("🟢 Нет активного маршрута")

            elif role == "needy":
                cur.execute(
                    "SELECT id, status FROM tickets WHERE needy_id = %s AND status IN ('open','assigned') LIMIT 1",
                    (related_id,),
                )
                ticket = cur.fetchone()
                if ticket:
                    status_label = "назначен волонтёр" if ticket["status"] == "assigned" else "ожидает"
                    lines.append(f"📦 Активная заявка #{ticket['id']} — {status_label}")
                else:
                    lines.append("🟢 Нет активных заявок")

            elif role == "shop":
                cur.execute(
                    "SELECT COUNT(*) as cnt FROM lots WHERE shop_id = %s AND status = 'active'",
                    (related_id,),
                )
                row = cur.fetchone()
                cnt = row["cnt"] if row else 0
                lines.append(f"🏪 Активных лотов: {cnt}")

        await message.answer("\n".join(lines), parse_mode="HTML")

    @bot_router.message(Command("unlink"))
    async def handle_unlink(message: Message):
        chat_id = str(message.chat.id)
        with get_db_cursor() as cur:
            cur.execute(
                "UPDATE users SET telegram_chat_id = NULL WHERE telegram_chat_id = %s RETURNING username",
                (chat_id,),
            )
            row = cur.fetchone()
        if row:
            await message.answer(
                f"✅ Telegram отвязан от аккаунта <b>{html.escape(row['username'])}</b>.\n"
                "Уведомления больше не будут приходить сюда.",
                parse_mode="HTML",
            )
        else:
            await message.answer("❓ Этот чат не был привязан ни к одному аккаунту.")

    @bot_router.message(Command("chat"))
    async def handle_chat_command(message: Message):
        await message.answer(
            "💬 Просто напишите сообщение в этот чат — оно будет переслано волонтёру/получателю, "
            "если у вас есть активный маршрут.",
        )

    @bot_router.message(lambda msg: bool(msg.text and msg.text.startswith("/")))
    async def handle_unknown_command(message: Message):
        await message.answer("❓ Неизвестная команда. Введите /help чтобы посмотреть список команд.")

    async def _ai_answer_or_escalate(message: Message, text: str, sender) -> None:
        """Free-form question: try the AI assistant; when it can't answer
        (or there is no API key), hand the question over to a human admin."""
        from backend import ai_service
        from backend import telegram_service as tgsvc

        role = sender["role"] if sender else "guest"
        username = sender["username"] if sender else None
        answer = await asyncio.to_thread(ai_service.ask_support_ai, text, role, username)

        if answer and answer.strip() != ai_service.ESCALATE:
            await message.answer(answer)
            return

        support_chat = os.getenv("SUPPORT_CHAT_ID", "")
        if support_chat:
            who = f"{role} {username}" if username else f"гость (chat {message.chat.id})"
            await asyncio.to_thread(
                tgsvc.send_message,
                support_chat,
                f"🆘 Вопрос пользователю требует администратора\n"
                f"От: {html.escape(who)}\n"
                f"Сообщение: {html.escape(text)}",
            )
            await message.answer(
                "🤝 Я не уверен в ответе, поэтому передал ваш вопрос администратору — "
                "он ответит вам здесь или на платформе."
            )
        else:
            await message.answer(
                "❓ Не могу ответить на этот вопрос. Напишите в поддержку на платформе "
                "или используйте /help для списка команд."
            )

    @bot_router.message(F.text & ~F.text.startswith('/'))
    async def handle_relay_message(message: Message):
        chat_id = str(message.chat.id)
        text = message.text or ""

        with get_db_cursor() as cur:
            cur.execute("SELECT id, role, related_id, username FROM users WHERE telegram_chat_id = %s", (chat_id,))
            sender = cur.fetchone()

        if not sender:
            await _ai_answer_or_escalate(message, text, None)
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
                # No relay counterpart — treat the message as a support question.
                await _ai_answer_or_escalate(message, text, sender)
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
            safe_sender = html.escape(sender_name or "")
            safe_text = html.escape(text)
            # notify_* uses a blocking httpx client (timeout up to 10s) — run it
            # off the event loop so the bot and FastAPI websockets don't stall.
            for nid in set(needy_ids):
                await asyncio.to_thread(tgsvc.notify_needy, nid, f"💬 Волонтёр {safe_sender}: {safe_text}")
            await message.answer("✅ Сообщение отправлено")

        elif role == 'needy':
            with get_db_cursor() as cur:
                cur.execute(
                    "SELECT assigned_volunteer_id FROM tickets WHERE needy_id = %s AND status = 'assigned' LIMIT 1",
                    (related_id,),
                )
                ticket = cur.fetchone()
            if not ticket or not ticket['assigned_volunteer_id']:
                # No relay counterpart — treat the message as a support question.
                await _ai_answer_or_escalate(message, text, sender)
                return
            safe_sender = html.escape(sender_name or "")
            safe_text = html.escape(text)
            await asyncio.to_thread(tgsvc.notify_volunteer, ticket['assigned_volunteer_id'], f"💬 Получатель {safe_sender}: {safe_text}")
            await message.answer("✅ Сообщение отправлено")

        else:
            # Shops/admins have no relay counterpart — route to the AI assistant.
            await _ai_answer_or_escalate(message, text, sender)

    import socket
    from aiogram.client.session.aiohttp import AiohttpSession

    # Force IPv4: Docker often resolves api.telegram.org to an IPv6 address
    # that aiohttp's TCPConnector can't reach, while httpx falls back to IPv4.
    session = AiohttpSession()
    session._connector_init["family"] = socket.AF_INET

    _bot = Bot(token=TELEGRAM_BOT_TOKEN, session=session)
    _dp  = Dispatcher()
    _dp.include_router(bot_router)
    return _bot, _dp


async def _register_commands(bot) -> None:
    """Push the command menu to Telegram so it shows up in the UI."""
    try:
        from aiogram.types import BotCommand
        await bot.set_my_commands([
            BotCommand(command="start",  description="Добро пожаловать / привязать аккаунт"),
            BotCommand(command="help",   description="Список команд"),
            BotCommand(command="status", description="Статус аккаунта и активных задач"),
            BotCommand(command="chat",   description="Как переписываться через бота"),
            BotCommand(command="unlink", description="Отвязать Telegram от SaveFood"),
        ])
        logging.info("[telegram] Bot commands registered")
    except Exception as e:
        logging.warning("[telegram] Failed to register commands: %s", e)


async def start_polling():
    """Start long-polling in a background asyncio task."""
    global _polling_task
    if not USE_POLLING or not TELEGRAM_BOT_TOKEN:
        return
    bot, dp = _build_bot_and_dp()
    if bot is None:
        return

    async def _run():
        await _register_commands(bot)
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
    # Verify the shared secret Telegram sends back in the header (configured
    # via setWebhook's `secret_token`). Without this anyone who finds the URL
    # can inject fake Updates and impersonate users.
    if TELEGRAM_WEBHOOK_SECRET:
        provided = request.headers.get("X-Telegram-Bot-Api-Secret-Token")
        if provided != TELEGRAM_WEBHOOK_SECRET:
            raise HTTPException(status_code=401, detail="Invalid webhook secret")
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
@limiter.limit("10/minute")
def init_telegram_link(request: Request, current_user: dict = Depends(get_current_user)):
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
        # UPSERT keeps DELETE+INSERT atomic. Without the unique index two
        # parallel /init-link calls could each delete the other's row and we'd
        # end up with two tokens (or one stale token + one live), depending on
        # interleaving. Now the latest call always wins, in a single statement.
        cur.execute(
            """
            INSERT INTO telegram_link_tokens (token, user_id, created_at)
            VALUES (%s, %s, %s)
            ON CONFLICT (user_id) DO UPDATE
              SET token = EXCLUDED.token, created_at = EXCLUDED.created_at
            """,
            (token, user_id, datetime.now(timezone.utc)),
        )

    return {
        "link": f"https://t.me/{BOT_NAME}?start=link_{token}",
        "bot_name": BOT_NAME,
        "already_linked": already_linked,
        "expires_in": 600,
    }
