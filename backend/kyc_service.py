"""Auto-KYC v1: AI pre-check of a needy person's eligibility document.

The moderator stays in the loop (the final approve/reject is still human —
§5), but the AI does the slow part: it reads the uploaded document, decides
whether it plausibly confirms social need, checks the name against the
profile and looks for editing artifacts. The verdict lands in needy.kyc_*
columns and is shown in the admin moderation queue, so a decision takes
seconds instead of minutes.

Runs in a daemon thread fired right after the document upload — same
fire-and-forget pattern as the loops in main.py. The document itself is
still deleted after moderation (§5); only the score/summary persist.
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
KYC_MODEL = os.getenv("KYC_MODEL", os.getenv("AI_MODEL", "gemini-2.5-flash"))

# score >= threshold → verdict 'likely_ok' (green hint for the moderator)
KYC_OK_THRESHOLD = 0.7
# score <= threshold → verdict 'likely_fraud' (red hint)
KYC_FRAUD_THRESHOLD = 0.3

# ── Auto-KYC v2 (роадмап v2.2): auto-approve at high confidence ──────────────
# Opt-in via env. Only 'likely_ok' AT OR ABOVE the (stricter) auto-approve
# score skips the human; 'review' and 'likely_fraud' always stay in the queue.
KYC_AUTO_APPROVE = os.getenv("KYC_AUTO_APPROVE", "").lower() in ("1", "true", "yes")
KYC_AUTO_APPROVE_SCORE = float(os.getenv("KYC_AUTO_APPROVE_SCORE", "0.85"))

_MIME_BY_EXT = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
    ".webp": "image/webp", ".pdf": "application/pdf",
}

SYSTEM_PROMPT = """\
Ты — система предварительной проверки документов платформы SaveFood (Казахстан).
Нуждающийся загрузил документ, подтверждающий право на продуктовую помощь
(справка о соц. статусе, удостоверение многодетной семьи, справка об инвалидности,
справка о доходах, пенсионное удостоверение и т.п.).

Верни СТРОГО один JSON-объект:
{
  "is_document": true|false,        // на фото вообще документ?
  "document_type": "краткое название типа документа или null",
  "supports_need": true|false,      // документ по смыслу подтверждает нуждаемость?
  "holder_name": "ФИО владельца из документа или null",
  "name_matches": true|false|null,  // совпадает ли с именем заявителя (null если не сравнить)
  "legible": true|false,            // текст читаем?
  "tampering_signs": true|false,    // следы редактирования/скриншот/фотошоп?
  "tampering_reason": "пояснение или null",
  "summary": "1-2 предложения для модератора на русском"
}

Имя заявителя в анкете будет передано отдельной строкой. Сравнивай имена мягко:
учитывай инициалы, порядок слов, транслитерацию (ru/kk/en).
"""


def _analyze(content: bytes, mime_type: str, applicant_name: str) -> Optional[Dict[str, Any]]:
    if not GEMINI_API_KEY or not content:
        return None
    try:
        with httpx.Client(timeout=60) as client:
            resp = client.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{KYC_MODEL}:generateContent",
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
                                "mime_type": mime_type,
                                "data": base64.b64encode(content).decode(),
                            }},
                            {"text": f"Имя заявителя в анкете: {applicant_name or 'не указано'}"},
                        ],
                    }],
                    "generationConfig": {
                        "responseMimeType": "application/json",
                        "maxOutputTokens": 1000,
                    },
                },
            )
        if resp.status_code != 200:
            logging.warning("[kyc] Gemini API %s: %s", resp.status_code, resp.text[:200])
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
        logging.warning("[kyc] document analysis failed: %s", e)
        return None


def _score(parsed: Dict[str, Any]) -> Dict[str, Any]:
    """Deterministic scoring of the AI's structured answer (0=fraud, 1=ok)."""
    notes = []
    if not parsed.get("is_document"):
        return {"score": 0.0, "verdict": "likely_fraud",
                "notes": "На фото не распознан документ. " + (parsed.get("summary") or "")}

    score = 0.5
    if parsed.get("supports_need"):
        score += 0.3
    else:
        score -= 0.3
        notes.append("документ не подтверждает нуждаемость")
    if parsed.get("name_matches") is True:
        score += 0.2
    elif parsed.get("name_matches") is False:
        score -= 0.3
        notes.append("имя в документе не совпадает с анкетой")
    if not parsed.get("legible"):
        score -= 0.2
        notes.append("текст плохо читаем")
    if parsed.get("tampering_signs"):
        score -= 0.4
        notes.append(f"признаки редактирования: {parsed.get('tampering_reason') or 'без деталей'}")

    score = max(0.0, min(1.0, round(score, 2)))
    if score >= KYC_OK_THRESHOLD:
        verdict = "likely_ok"
    elif score <= KYC_FRAUD_THRESHOLD:
        verdict = "likely_fraud"
    else:
        verdict = "review"

    summary = parsed.get("summary") or ""
    doc_type = parsed.get("document_type")
    prefix = f"[{doc_type}] " if doc_type else ""
    tail = ("; ".join(notes)) if notes else ""
    note_text = (prefix + summary + ((" — " + tail) if tail else "")).strip()
    return {"score": score, "verdict": verdict, "notes": note_text[:1000]}


def _save_result(needy_id: int, score: Optional[float], verdict: str, notes: str):
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE needy SET kyc_score = %s, kyc_verdict = %s, kyc_notes = %s, kyc_checked_at = %s WHERE id = %s",
            (score, verdict, notes, datetime.now(timezone.utc), needy_id),
        )


def should_auto_approve(verdict: str, score, enabled: bool = None, threshold: float = None) -> bool:
    """Pure decision: auto-approve only confident 'likely_ok' verdicts."""
    enabled = KYC_AUTO_APPROVE if enabled is None else enabled
    threshold = KYC_AUTO_APPROVE_SCORE if threshold is None else threshold
    return bool(enabled and verdict == "likely_ok" and score is not None and score >= threshold)


def _auto_approve(needy_id: int, document_path: str, score: float):
    """Approve without a human: same effects as the moderation endpoint (§5) —
    status flip, document removal, notification — plus an audit trail."""
    from backend.database import log_action
    from backend.needy import db as needy_db
    from backend import telegram_service

    needy_db.set_needy_status(needy_id, "approved")
    try:
        if os.path.isfile(document_path):
            os.remove(document_path)
        needy_db.create_or_update_profile(needy_id, None, None, None, None, document=None)
    except Exception:
        logging.exception("[kyc] auto-approve: document cleanup failed for needy %s", needy_id)
    log_action("auto-kyc", "kyc_auto_approve", "needy", needy_id,
               f"Auto-approved by AI KYC v2 (score {score:.2f})")
    msg = "Ваша анкета одобрена автоматической проверкой — можете создавать заявки на получение продуктов."
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
            (needy_id, "moderation_approved", msg, datetime.now(timezone.utc)),
        )
    try:
        telegram_service.notify_needy(needy_id, f"✅ {msg}")
    except Exception:
        pass
    logging.info("[kyc] needy %s auto-approved (score %.2f)", needy_id, score)


def run_kyc_check(needy_id: int, document_path: str, applicant_name: str):
    """Blocking analysis — call via start_kyc_check (thread) from endpoints."""
    try:
        if not os.path.isfile(document_path):
            return
        ext = os.path.splitext(document_path)[1].lower()
        mime = _MIME_BY_EXT.get(ext, "image/jpeg")
        with open(document_path, "rb") as f:
            content = f.read()
        parsed = _analyze(content, mime, applicant_name)
        if parsed is None:
            # AI unavailable → the queue falls back to fully manual review.
            _save_result(needy_id, None, "unchecked", "ИИ-проверка недоступна, проверьте вручную")
            return
        result = _score(parsed)
        notes = result["notes"]
        auto = should_auto_approve(result["verdict"], result["score"])
        if auto:
            notes = f"[авто-одобрено ИИ] {notes}"[:1000]
        _save_result(needy_id, result["score"], result["verdict"], notes)
        if auto:
            # Only auto-approve while still pending — a moderator may have
            # already made a manual decision during the AI call.
            with get_db_cursor() as cur:
                cur.execute("SELECT status FROM needy WHERE id = %s", (needy_id,))
                row = cur.fetchone()
            if row and row["status"] == "pending":
                _auto_approve(needy_id, document_path, result["score"])
        logging.info("[kyc] needy %s: %s (%.2f)", needy_id, result["verdict"], result["score"])
    except Exception as e:
        logging.warning("[kyc] check for needy %s failed: %s", needy_id, e)


def start_kyc_check(needy_id: int, document_path: str, applicant_name: str):
    threading.Thread(
        target=run_kyc_check, args=(needy_id, document_path, applicant_name), daemon=True
    ).start()
