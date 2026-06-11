"""AI support assistant for the Telegram bot (Google Gemini).

Answers free-form user questions about the platform via the Gemini API.
If the model is not confident (or the API key is missing), the caller is
expected to escalate the question to a human administrator (SUPPORT_CHAT_ID).

Synchronous httpx client — call through asyncio.to_thread from async handlers.
"""
import os
import logging
import httpx

# GEMINI_API_KEY is the documented name; GOOGLE_API_KEY accepted as a fallback
# because Google's own SDKs read both.
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "") or os.getenv("GOOGLE_API_KEY", "")
# Flash: fast and cheap — right size for a support FAQ bot.
AI_MODEL = os.getenv("AI_MODEL", "gemini-2.5-flash")

# The sentinel the model returns when it cannot answer reliably.
ESCALATE = "ESCALATE"

SYSTEM_PROMPT = """\
Ты — помощник службы поддержки платформы SaveFood (savefood — спасение еды).

О платформе:
- SaveFood соединяет магазины (отдают еду с истекающим сроком годности), \
волонтёров (доставляют) и нуждающихся (получают помощь бесплатно).
- Магазин публикует «лот» (название, категория, количество кг, срок годности, \
адрес, время выдачи). Лот автоматически снимается за 24 часа до истечения срока.
- Нуждающийся регистрируется, загружает документ о статусе, модератор одобряет \
в течение 24 часов. Получать помощь можно не чаще раза в неделю. Одновременно \
может быть только одна активная заявка.
- Нуждающийся выбирает лот на карте и оформляет заявку: доставка волонтёром \
или самовывоз. При самовывозе магазин сканирует QR-код получателя (SF-<номер>).
- Волонтёр берёт лот на карте, приложение строит маршрут: магазин → получатели. \
В магазине жмёт «Я забрал», получатели получают уведомление с временем прибытия. \
Доставка подтверждается сканом QR-кода получателя + GPS-проверкой (радиус 100 м).
- Если получатель не открыл дверь — волонтёр жмёт «Не открыли дверь» (после \
3 попыток заявка возвращается в очередь).
- Команды бота: /start — привязка аккаунта, /help — список команд, /status — \
статус аккаунта, /chat — как переписываться, /unlink — отвязать Telegram.
- Текстовые сообщения боту пересылаются второй стороне активной доставки \
(волонтёр ↔ получатель).

Правила ответа:
- Отвечай кратко (1-4 предложения), дружелюбно, на языке пользователя \
(по умолчанию русский).
- Отвечай ТОЛЬКО на вопросы о платформе SaveFood и её использовании.
- Если не знаешь ответа, не уверен, либо вопрос требует действий человека \
(жалоба, спор, разблокировка, изменение чужих данных, возврат, инцидент, \
поведение другого пользователя) — ответь ровно одним словом: ESCALATE
"""


def ask_support_ai(question: str, role: str = "guest", username: str = None) -> str | None:
    """Return the assistant's answer, the ESCALATE sentinel, or None on failure."""
    if not GEMINI_API_KEY or not (question or "").strip():
        return None
    user_line = f"Пользователь (роль: {role}"
    if username:
        user_line += f", логин: {username}"
    user_line += f") спрашивает:\n{question.strip()}"
    try:
        with httpx.Client(timeout=20) as client:
            resp = client.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{AI_MODEL}:generateContent",
                # Key goes in a header, not the query string, so it never lands
                # in access logs or tracebacks with the full URL.
                headers={
                    "x-goog-api-key": GEMINI_API_KEY,
                    "content-type": "application/json",
                },
                json={
                    "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
                    "contents": [{"role": "user", "parts": [{"text": user_line}]}],
                    "generationConfig": {"maxOutputTokens": 500},
                },
            )
        if resp.status_code != 200:
            logging.warning("[ai] Gemini API %s: %s", resp.status_code, resp.text[:200])
            return None
        candidates = resp.json().get("candidates", [])
        if not candidates:
            return None
        parts = candidates[0].get("content", {}).get("parts", [])
        text = "".join(p.get("text", "") for p in parts).strip()
        return text or None
    except Exception as e:
        logging.warning("[ai] request failed: %s", e)
        return None
