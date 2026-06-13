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
from backend import kyc_crypto

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "") or os.getenv("GOOGLE_API_KEY", "")
KYC_MODEL = os.getenv("KYC_MODEL", os.getenv("AI_MODEL", "gemini-2.5-flash"))

# score >= threshold → verdict 'likely_ok' (green hint for the moderator)
KYC_OK_THRESHOLD = 0.7
# score <= threshold → verdict 'likely_fraud' (red hint)
KYC_FRAUD_THRESHOLD = 0.3

# ── Single source of truth for KYC labels (shared by needy + volunteer) ──────
# AI verdict labels (stored in *.kyc_verdict, rendered by the admin badge).
VERDICT_OK = "likely_ok"
VERDICT_REVIEW = "review"
VERDICT_FRAUD = "likely_fraud"
VERDICT_UNCHECKED = "unchecked"  # AI unavailable → fall back to manual review

# Moderation lifecycle statuses (stored in needy.status / volunteers.status).
STATUS_PENDING = "pending"
STATUS_APPROVED = "approved"
STATUS_REJECTED = "rejected"

# KYC is fully automated (§58): the verdict alone decides — 'likely_ok' →
# approve, 'likely_fraud' → reject, 'review'/'unchecked' → stay pending (retried).
# There is no human-in-the-loop and no opt-in env gate any more.

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


def _analyze(content: bytes, mime_type: str, applicant_name: str,
             system_prompt: str = SYSTEM_PROMPT) -> Optional[Dict[str, Any]]:
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
                    "system_instruction": {"parts": [{"text": system_prompt}]},
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


def _finalize_score(score: float, notes: list, parsed: Dict[str, Any]) -> Dict[str, Any]:
    """Shared tail of both scorers: clamp the score, pick the verdict from the
    thresholds, and assemble the moderator note. Kept in one place so needy and
    volunteer verdicts always mean the same thing (they share kyc_score/verdict
    columns and the same admin badge)."""
    score = max(0.0, min(1.0, round(score, 2)))
    if score >= KYC_OK_THRESHOLD:
        verdict = VERDICT_OK
    elif score <= KYC_FRAUD_THRESHOLD:
        verdict = VERDICT_FRAUD
    else:
        verdict = VERDICT_REVIEW

    summary = parsed.get("summary") or ""
    doc_type = parsed.get("document_type")
    prefix = f"[{doc_type}] " if doc_type else ""
    tail = ("; ".join(notes)) if notes else ""
    note_text = (prefix + summary + ((" — " + tail) if tail else "")).strip()
    return {"score": score, "verdict": verdict, "notes": note_text[:1000]}


def _score(parsed: Dict[str, Any]) -> Dict[str, Any]:
    """Deterministic scoring of the AI's structured answer (0=fraud, 1=ok)."""
    notes = []
    if not parsed.get("is_document"):
        return {"score": 0.0, "verdict": VERDICT_FRAUD,
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

    return _finalize_score(score, notes, parsed)


def _save_result(needy_id: int, score: Optional[float], verdict: str, notes: str):
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE needy SET kyc_score = %s, kyc_verdict = %s, kyc_notes = %s, kyc_checked_at = %s WHERE id = %s",
            (score, verdict, notes, datetime.now(timezone.utc), needy_id),
        )


def _auto_approve(needy_id: int, document_path: str, score: float) -> bool:
    """Approve without a human: atomic status flip + notification + audit trail.
    The flip is conditional on the row still being 'pending'. The document is NOT
    deleted — it is retained encrypted-at-rest for accountability. Returns True
    only if we approved."""
    from backend.database import log_action
    from backend.needy import db as needy_db
    from backend import telegram_service

    if needy_db.set_needy_status(needy_id, STATUS_APPROVED, expected_status=STATUS_PENDING) is None:
        logging.info("[kyc] needy %s no longer pending; skipping auto-approve", needy_id)
        return False
    log_action("auto-kyc", "kyc_auto_approve", "needy", needy_id,
               f"Auto-approved by AI KYC (score {score:.2f})")
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
    return True


def _auto_reject(needy_id: int, score, notes: str) -> bool:
    """Reject without a human when the AI is confident the document is invalid.
    The account stays usable for a re-upload (upload moves it back to pending).
    The document is retained encrypted for accountability. Returns True only if
    we rejected (row was still pending)."""
    from backend.database import log_action
    from backend.needy import db as needy_db
    from backend import telegram_service

    if needy_db.set_needy_status(needy_id, STATUS_REJECTED, expected_status=STATUS_PENDING) is None:
        return False
    log_action("auto-kyc", "kyc_auto_reject", "needy", needy_id,
               f"Auto-rejected by AI KYC (score {score})")
    msg = ("Документ не прошёл автоматическую проверку. Загрузите корректный "
           "документ, подтверждающий право на помощь.")
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
            (needy_id, "moderation_rejected", msg, datetime.now(timezone.utc)),
        )
    try:
        telegram_service.notify_needy(needy_id, f"⚠️ {msg}")
    except Exception:
        pass
    logging.info("[kyc] needy %s auto-rejected (score %s)", needy_id, score)
    return True


def _run_kyc_pipeline(entity_id, document_path, applicant_name, *,
                      system_prompt, scorer, saver, approver, rejecter, label):
    """Fully automated KYC for both entity types — NO human in the loop (§58):
    read the document, run the AI analysis, score it, persist the verdict, and
    auto-decide. Confident 'likely_ok' → approve; confident 'likely_fraud' →
    reject (account can re-upload); anything inconclusive ('review') or with the
    AI unavailable ('unchecked') stays pending and is retried by kyc_retry_tick
    until a confident verdict — it is never escalated to a person."""
    try:
        if not os.path.isfile(document_path):
            return
        ext = os.path.splitext(document_path)[1].lower()
        mime = _MIME_BY_EXT.get(ext, "image/jpeg")
        # Decrypt in memory only — the file on disk stays encrypted at rest.
        content = kyc_crypto.read_decrypted(document_path)
        parsed = _analyze(content, mime, applicant_name, system_prompt=system_prompt)
        if parsed is None:
            # AI unavailable → stay pending; kyc_retry_tick will re-run it later.
            saver(entity_id, None, VERDICT_UNCHECKED, "ИИ-проверка недоступна, будет повторена автоматически")
            return
        result = scorer(parsed)
        # Persist the verdict FIRST, so an entity is never left auto-approved
        # with a null KYC record if a later step fails.
        saver(entity_id, result["score"], result["verdict"], result["notes"])
        if result["verdict"] == VERDICT_OK:
            # Atomically claim the row only if still pending; re-stamp the note
            # only on an actual approval so it reflects reality.
            if approver(entity_id, document_path, result["score"]):
                saver(entity_id, result["score"], result["verdict"],
                      f"[авто-одобрено ИИ] {result['notes']}"[:1000])
        elif result["verdict"] == VERDICT_FRAUD:
            if rejecter(entity_id, result["score"], result["notes"]):
                saver(entity_id, result["score"], result["verdict"],
                      f"[авто-отклонено ИИ] {result['notes']}"[:1000])
        # VERDICT_REVIEW → stay pending (the dashboard banner prompts a clearer
        # re-upload); retrying the same document would yield the same score.
        logging.info("[kyc] %s %s: %s (%.2f)", label, entity_id, result["verdict"], result["score"])
    except Exception as e:
        logging.warning("[kyc] %s check for %s failed: %s", label, entity_id, e)


def run_kyc_check(needy_id: int, document_path: str, applicant_name: str):
    """Blocking analysis — call via start_kyc_check (thread) from endpoints."""
    _run_kyc_pipeline(
        needy_id, document_path, applicant_name,
        system_prompt=SYSTEM_PROMPT, scorer=_score, saver=_save_result,
        approver=_auto_approve, rejecter=_auto_reject, label="needy",
    )


def start_kyc_check(needy_id: int, document_path: str, applicant_name: str):
    threading.Thread(
        target=run_kyc_check, args=(needy_id, document_path, applicant_name), daemon=True
    ).start()


# ── Volunteer identity KYC (§58) ─────────────────────────────────────────────
# A volunteer physically picks food up from shops and carries it to recipients,
# so the fraud we guard against is identity (anyone grabbing food and vanishing),
# not eligibility. Same fully-automated pipeline as the needy check — no human in
# the loop — reading an *identity* document, retained encrypted-at-rest (§58).

VOLUNTEER_SYSTEM_PROMPT = """\
Ты — система верификации личности волонтёров платформы SaveFood (Казахстан).
Волонтёр загрузил документ, удостоверяющий личность (удостоверение личности РК,
паспорт, водительское удостоверение и т.п.). Цель — убедиться, что это настоящий
документ реального человека, а не чужое/поддельное/случайное фото.

Верни СТРОГО один JSON-объект:
{
  "is_document": true|false,        // на фото вообще документ?
  "is_id_document": true|false,     // это именно удостоверение личности (а не справка/чек/случайное фото)?
  "document_type": "краткое название типа документа или null",
  "holder_name": "ФИО владельца из документа или null",
  "name_matches": true|false|null,  // совпадает ли с именем волонтёра в анкете (null если не сравнить)
  "legible": true|false,            // текст и фото читаемы?
  "tampering_signs": true|false,    // следы редактирования/скриншот/фотошоп?
  "tampering_reason": "пояснение или null",
  "summary": "1-2 предложения для модератора на русском"
}

Имя волонтёра в анкете будет передано отдельной строкой. Сравнивай имена мягко:
учитывай инициалы, порядок слов, транслитерацию (ru/kk/en).
"""


def _score_volunteer(parsed: Dict[str, Any]) -> Dict[str, Any]:
    """Deterministic scoring of the AI's structured answer for an identity doc."""
    if not parsed.get("is_document") or not parsed.get("is_id_document"):
        return {"score": 0.0, "verdict": VERDICT_FRAUD,
                "notes": "На фото не распознано удостоверение личности. " + (parsed.get("summary") or "")}

    notes = []
    score = 0.6
    if parsed.get("name_matches") is True:
        score += 0.25
    elif parsed.get("name_matches") is False:
        score -= 0.35
        notes.append("имя в документе не совпадает с анкетой")
    if not parsed.get("legible"):
        score -= 0.25
        notes.append("документ плохо читаем")
    if parsed.get("tampering_signs"):
        score -= 0.5
        notes.append(f"признаки редактирования: {parsed.get('tampering_reason') or 'без деталей'}")

    return _finalize_score(score, notes, parsed)


def _auto_approve_volunteer(vol_id: int, document_path: str, score: float) -> bool:
    """Approve a volunteer without a human. The flip is conditional on the row
    still being 'pending'. The identity document is retained encrypted-at-rest
    for accountability (not deleted). Returns True only if we approved."""
    from backend.database import log_action
    from backend.volunteer import db as vol_db
    from backend import telegram_service

    if vol_db.set_volunteer_status(vol_id, STATUS_APPROVED, expected_status=STATUS_PENDING) is None:
        logging.info("[kyc] volunteer %s no longer pending; skipping auto-approve", vol_id)
        return False
    log_action("auto-kyc", "kyc_auto_approve", "volunteer", vol_id,
               f"Auto-approved volunteer by AI KYC (score {score:.2f})")
    msg = "Ваш аккаунт волонтёра подтверждён автоматической проверкой — можно брать маршруты."
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
            (vol_id, "moderation_approved", msg, datetime.now(timezone.utc)),
        )
    try:
        telegram_service.notify_volunteer(vol_id, f"✅ {msg}")
    except Exception:
        pass
    logging.info("[kyc] volunteer %s auto-approved (score %.2f)", vol_id, score)
    return True


def _auto_reject_volunteer(vol_id: int, score, notes: str) -> bool:
    """Reject a volunteer without a human when the AI is confident the identity
    document is invalid. The account can re-upload (upload moves it back to
    pending). Document retained encrypted. Returns True only if we rejected."""
    from backend.database import log_action
    from backend.volunteer import db as vol_db
    from backend import telegram_service

    if vol_db.set_volunteer_status(vol_id, STATUS_REJECTED, expected_status=STATUS_PENDING) is None:
        return False
    log_action("auto-kyc", "kyc_auto_reject", "volunteer", vol_id,
               f"Auto-rejected volunteer by AI KYC (score {score})")
    msg = ("Удостоверение не прошло автоматическую проверку. Загрузите корректный "
           "документ, удостоверяющий личность, чтобы брать маршруты.")
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
            (vol_id, "moderation_rejected", msg, datetime.now(timezone.utc)),
        )
    try:
        telegram_service.notify_volunteer(vol_id, f"⚠️ {msg}")
    except Exception:
        pass
    logging.info("[kyc] volunteer %s auto-rejected (score %s)", vol_id, score)
    return True


def run_volunteer_kyc_check(vol_id: int, document_path: str, applicant_name: str):
    """Blocking analysis — call via start_volunteer_kyc_check (thread)."""
    from backend.volunteer import db as vol_db
    _run_kyc_pipeline(
        vol_id, document_path, applicant_name,
        system_prompt=VOLUNTEER_SYSTEM_PROMPT, scorer=_score_volunteer,
        saver=vol_db.save_volunteer_kyc, approver=_auto_approve_volunteer,
        rejecter=_auto_reject_volunteer, label="volunteer",
    )


def start_volunteer_kyc_check(vol_id: int, document_path: str, applicant_name: str):
    threading.Thread(
        target=run_volunteer_kyc_check, args=(vol_id, document_path, applicant_name), daemon=True
    ).start()
