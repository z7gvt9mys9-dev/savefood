"""Pre-publication moderation of recipient delivery photos (Gemini Vision).

A recipient photographs the food they received; that photo feeds the public
Impact feed (§36) — a PR surface watched by media and city administrations.
Until now it appeared there instantly, so a single trolling/inappropriate shot
became public with zero review. This module gates every photo:

1. The photo lands as `delivery_photo_status = 'pending'` — the feed only
   shows `'approved'`, so nothing is public until a decision is made.
2. One Vision call asks "is this actually food, and is it appropriate?" and
   leaves a colored verdict + note in the admin queue, so a human decides in
   seconds. When PHOTO_AUTO_MODERATE is enabled, confident food is
   auto-approved and clearly inappropriate content is auto-rejected.

Same shape as kyc_service.py / receipt_service.py: synchronous httpx call,
fired fire-and-forget from a daemon thread right after the upload.
"""
import base64
import json
import logging
import os
import threading
from datetime import datetime, timezone
from typing import Any, Dict, Optional

import httpx

from backend.database import get_db_cursor

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "") or os.getenv("GOOGLE_API_KEY", "")
PHOTO_MODEL = os.getenv("PHOTO_MODEL", os.getenv("AI_MODEL", "gemini-2.5-flash"))

# score >= → verdict 'food' (green hint); score <= → 'rejected-ish' (red hint)
PHOTO_OK_THRESHOLD = 0.7
PHOTO_BAD_THRESHOLD = 0.3

# ── Auto-moderation (opt-in via env) ─────────────────────────────────────────
# Only confident food is auto-approved; clearly inappropriate content is
# auto-rejected. Everything in between waits for a human in the queue.
PHOTO_AUTO_MODERATE = os.getenv("PHOTO_AUTO_MODERATE", "").lower() in ("1", "true", "yes")
PHOTO_AUTO_APPROVE_SCORE = float(os.getenv("PHOTO_AUTO_APPROVE_SCORE", "0.85"))

SYSTEM_PROMPT = """\
Ты — модератор публичной витрины платформы SaveFood (Казахстан).
Получатель помощи загрузил фото полученных им продуктов — это фото попадёт в
ПУБЛИЧНУЮ ленту, которую смотрят СМИ и городские администрации.

Твоя задача — решить, можно ли это фото публиковать. На фото должна быть еда
(продукты, готовые блюда, пакеты/коробки с продуктами). Недопустимо:
люди крупным планом, документы с персональными данными, оскорбительный или
непристойный контент, насилие, мемы/скриншоты, реклама, изображения не по теме.

Верни СТРОГО один JSON-объект без пояснений:
{
  "is_food": true|false,            // на фото действительно еда/продукты?
  "depicts": "1-3 слова: что изображено",
  "inappropriate": true|false,      // непристойное/оскорбительное/не по теме/персданные?
  "inappropriate_reason": "пояснение или null",
  "quality_ok": true|false,         // фото не размытое/не тёмное, еда различима?
  "summary": "1-2 предложения для модератора на русском"
}
"""


def _analyze(content: bytes, mime_type: str) -> Optional[Dict[str, Any]]:
    if not GEMINI_API_KEY or not content:
        return None
    try:
        with httpx.Client(timeout=60) as client:
            resp = client.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{PHOTO_MODEL}:generateContent",
                # Key in a header — never in the query string (see ai_service.py).
                headers={
                    "x-goog-api-key": GEMINI_API_KEY,
                    "content-type": "application/json",
                },
                json={
                    "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
                    "contents": [{
                        "role": "user",
                        "parts": [
                            {"inline_data": {
                                "mime_type": mime_type or "image/jpeg",
                                "data": base64.b64encode(content).decode(),
                            }},
                            {"text": "Проверь это фото для публичной ленты."},
                        ],
                    }],
                    "generationConfig": {
                        "responseMimeType": "application/json",
                        "maxOutputTokens": 1000,
                    },
                },
            )
        if resp.status_code != 200:
            logging.warning("[photo] Gemini API %s: %s", resp.status_code, resp.text[:200])
            return None
        candidates = resp.json().get("candidates", [])
        if not candidates:
            return None
        parts = candidates[0].get("content", {}).get("parts", [])
        text = "".join(p.get("text", "") for p in parts).strip()
        if text.startswith("```"):
            text = text.strip("`")
            if text.startswith("json"):
                text = text[4:]
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else None
    except Exception as e:
        logging.warning("[photo] analysis failed: %s", e)
        return None


def score_photo(parsed: Dict[str, Any]) -> Dict[str, Any]:
    """Deterministic scoring of the AI's structured answer (0=reject, 1=ok)."""
    notes = []
    # Inappropriate content is a hard veto regardless of "is_food".
    if parsed.get("inappropriate"):
        reason = parsed.get("inappropriate_reason") or "недопустимый контент"
        return {"score": 0.0, "verdict": "inappropriate",
                "notes": f"Недопустимо: {reason}. " + (parsed.get("summary") or ""),
                "inappropriate": True}

    score = 0.5
    if parsed.get("is_food"):
        score += 0.4
    else:
        score -= 0.4
        notes.append(f"на фото не еда ({parsed.get('depicts') or '?'})")
    if parsed.get("quality_ok") is False:
        score -= 0.2
        notes.append("низкое качество фото")

    score = max(0.0, min(1.0, round(score, 2)))
    if score >= PHOTO_OK_THRESHOLD:
        verdict = "food"
    elif score <= PHOTO_BAD_THRESHOLD:
        verdict = "not_food"
    else:
        verdict = "review"

    summary = (parsed.get("summary") or "").strip()
    tail = ("; ".join(notes)) if notes else ""
    note_text = (summary + ((" — " + tail) if tail else "")).strip()
    return {"score": score, "verdict": verdict, "notes": note_text[:1000],
            "inappropriate": False}


def decide(result: Dict[str, Any], enabled: bool = None, threshold: float = None) -> Optional[str]:
    """Pure auto-moderation decision → 'approved' | 'rejected' | None (queue)."""
    enabled = PHOTO_AUTO_MODERATE if enabled is None else enabled
    threshold = PHOTO_AUTO_APPROVE_SCORE if threshold is None else threshold
    if not enabled:
        return None
    if result.get("inappropriate"):
        return "rejected"
    if result["verdict"] == "food" and result["score"] >= threshold:
        return "approved"
    return None


def _save_result(ticket_id: int, photo_url: str, status: str, verdict: Optional[str],
                 score: Optional[float], notes: str, reviewed: bool) -> bool:
    reviewed_at = datetime.now(timezone.utc) if reviewed else None
    with get_db_cursor() as cur:
        # Only touch a row that is still 'pending' AND still points at the photo
        # this check analysed: a human decision must never be overwritten, and a
        # re-upload resets the status to 'pending' for a *different* file — the
        # stale verdict must not land on (or auto-reject) the new photo.
        cur.execute(
            """
            UPDATE tickets
               SET delivery_photo_status = %s,
                   delivery_photo_ai_verdict = %s,
                   delivery_photo_ai_score = %s,
                   delivery_photo_ai_notes = %s,
                   delivery_photo_reviewed_at = %s
             WHERE id = %s AND delivery_photo_status = 'pending'
               AND delivery_photo = %s
            """,
            (status, verdict, score, notes, reviewed_at, ticket_id, photo_url),
        )
        return cur.rowcount > 0


def run_photo_check(ticket_id: int, photo_path: str):
    """Blocking analysis — call via start_photo_check (thread) from endpoints."""
    try:
        if not os.path.isfile(photo_path):
            return
        photo_url = f"/volunteer_uploads/{os.path.basename(photo_path)}"
        ext = os.path.splitext(photo_path)[1].lower()
        mime = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png",
                "webp": "image/webp"}.get(ext.lstrip("."), "image/jpeg")
        with open(photo_path, "rb") as f:
            content = f.read()
        parsed = _analyze(content, mime)
        if parsed is None:
            # AI unavailable → stays pending for fully manual review.
            _save_result(ticket_id, photo_url, "pending", "unchecked", None,
                         "ИИ-проверка недоступна, проверьте вручную", reviewed=False)
            return
        result = score_photo(parsed)
        auto = decide(result)
        notes = result["notes"]
        if auto == "approved":
            notes = f"[авто-одобрено ИИ] {notes}"[:1000]
        elif auto == "rejected":
            notes = f"[авто-отклонено ИИ] {notes}"[:1000]
        applied = _save_result(ticket_id, photo_url, auto or "pending", result["verdict"],
                               result["score"], notes, reviewed=auto is not None)
        if applied and auto == "rejected":
            # Drop the analysed file (we know its exact path) so it never leaks;
            # the row keeps the verdict for the admin's rejected queue.
            try:
                os.remove(photo_path)
            except OSError:
                pass
        logging.info("[photo] ticket %s: %s (%.2f) → %s%s",
                     ticket_id, result["verdict"], result["score"], auto or "pending",
                     "" if applied else " (stale, not applied)")
    except Exception as e:
        logging.warning("[photo] check for ticket %s failed: %s", ticket_id, e)


def start_photo_check(ticket_id: int, photo_path: str):
    threading.Thread(
        target=run_photo_check, args=(ticket_id, photo_path), daemon=True
    ).start()
