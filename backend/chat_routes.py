"""REST endpoints for the in-app ticket chat (§53)."""
import html

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from backend import chat, telegram_service, push_service
from backend.auth import get_current_user
from backend.limiter import limiter

router = APIRouter(tags=["chat"])

MAX_BODY = 2000


class MessageIn(BaseModel):
    body: str = Field(..., min_length=1, max_length=MAX_BODY)


def _require_participant(ticket_id: int, current_user: dict):
    ctx = chat.get_ticket_context(ticket_id)
    if not ctx:
        raise HTTPException(status_code=404, detail="Ticket not found")
    role = chat.participant_role(current_user, ctx)
    if role is None:
        raise HTTPException(status_code=403, detail="Forbidden")
    return ctx, role


@router.get("/tickets/{ticket_id}/messages")
def get_messages(ticket_id: int, after_id: int = 0, current_user: dict = Depends(get_current_user)):
    _require_participant(ticket_id, current_user)
    return chat.list_messages(ticket_id, after_id=after_id)


@router.post("/tickets/{ticket_id}/messages")
@limiter.limit("30/minute")
def post_message(ticket_id: int, payload: MessageIn, request: Request, current_user: dict = Depends(get_current_user)):
    ctx, role = _require_participant(ticket_id, current_user)
    # Admins observe; the live conversation is between the two real parties.
    if role == "admin":
        raise HTTPException(status_code=403, detail="Администратор не участвует в чате")
    if ctx["status"] != "assigned":
        raise HTTPException(status_code=400, detail="Чат доступен, пока заявка в работе у волонтёра")
    if not ctx["assigned_volunteer_id"]:
        raise HTTPException(status_code=400, detail="На заявку ещё не назначен волонтёр")

    sender_id = ctx["needy_id"] if role == "needy" else ctx["assigned_volunteer_id"]
    msg = chat.add_message(ticket_id, role, sender_id, payload.body)

    # Best-effort mirrors so the counterpart sees it without the page open.
    safe = html.escape(payload.body)
    try:
        if role == "needy":
            telegram_service.notify_volunteer(ctx["assigned_volunteer_id"], f"💬 Получатель: {safe}")
            push_service.notify_role("volunteer", ctx["assigned_volunteer_id"], f"Сообщение от получателя: {payload.body}", url="/volunteer")
        else:
            telegram_service.notify_needy(ctx["needy_id"], f"💬 Волонтёр: {safe}")
            push_service.notify_role("needy", ctx["needy_id"], f"Сообщение от волонтёра: {payload.body}", url="/needy")
    except Exception:
        pass
    return msg
