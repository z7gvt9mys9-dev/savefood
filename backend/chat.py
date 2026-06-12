"""In-app chat (§53): volunteer↔recipient messages scoped to one ticket.

Replaces the Telegram-only relay (§22.7) for the majority of users who never
linked Telegram. The thread for ticket T is visible to exactly three parties:
the recipient who owns T, the volunteer assigned to T, and any admin. Posting is
only allowed while the ticket is 'assigned' (a volunteer is actually on it) so a
chat can't be opened against a stranger.

A posted message still fans out to Telegram + Web Push so the counterpart sees
it even when they don't have the page open — the in-app thread is the source of
truth, the pushes are best-effort mirrors.
"""
from typing import Any, Dict, List, Optional

from backend.database import get_db_cursor


def get_ticket_context(ticket_id: int) -> Optional[Dict[str, Any]]:
    """Returns the ticket's chat participants + status, or None if no ticket."""
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, needy_id, assigned_volunteer_id, status FROM tickets WHERE id = %s",
            (ticket_id,),
        )
        row = cur.fetchone()
    return dict(row) if row else None


def participant_role(current_user: dict, ctx: Dict[str, Any]) -> Optional[str]:
    """Map the caller to their role in this thread, or None if not a participant.

    Returns 'needy' / 'volunteer' / 'admin' — the value stored as sender_role.
    """
    role = current_user.get("role")
    if role == "admin":
        return "admin"
    related = current_user.get("related_id")
    if role == "needy" and related == ctx["needy_id"]:
        return "needy"
    if role == "volunteer" and related is not None and related == ctx["assigned_volunteer_id"]:
        return "volunteer"
    return None


def list_messages(ticket_id: int, after_id: int = 0) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT id, sender_role, sender_id, body, created_at
            FROM ticket_messages
            WHERE ticket_id = %s AND id > %s
            ORDER BY id ASC
            """,
            (ticket_id, after_id),
        )
        return [dict(r) for r in cur.fetchall()]


def add_message(ticket_id: int, sender_role: str, sender_id: int, body: str) -> Dict[str, Any]:
    with get_db_cursor() as cur:
        cur.execute(
            """
            INSERT INTO ticket_messages (ticket_id, sender_role, sender_id, body)
            VALUES (%s, %s, %s, %s)
            RETURNING id, sender_role, sender_id, body, created_at
            """,
            (ticket_id, sender_role, sender_id, body),
        )
        return dict(cur.fetchone())
