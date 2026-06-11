"""Receipt OCR + item classification + anti-fraud (Gemini Vision).

A shop photographs its write-off receipt; one VLM call extracts the merchant,
date, total and line items, normalizes item names and maps each item onto the
platform's four lot categories. Deterministic anti-fraud rules then score the
receipt (stale/future date, duplicates, AI authenticity verdict) before any
lot is created from it.

Synchronous httpx client (same style as ai_service.py) — endpoints calling it
are sync `def`, so FastAPI runs them in its threadpool.
"""
import base64
import hashlib
import json
import logging
import os
from datetime import date, datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

import httpx

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "") or os.getenv("GOOGLE_API_KEY", "")
# Vision-capable flash model: receipts are short documents, flash is enough.
OCR_MODEL = os.getenv("OCR_MODEL", os.getenv("AI_MODEL", "gemini-2.5-flash"))

# The platform's lot categories (must match the frontend selects).
LOT_CATEGORIES = ["Выпечка", "Овощи/Фрукты", "Готовая еда", "Молочные продукты"]

# Anti-fraud thresholds.
RECEIPT_MAX_AGE_HOURS = int(os.getenv("RECEIPT_MAX_AGE_HOURS", "48"))
FRAUD_REJECT_THRESHOLD = 0.7   # score >= → receipt rejected outright
FRAUD_FLAG_THRESHOLD = 0.4     # score >= → parsed but flagged for attention

SYSTEM_PROMPT = f"""\
Ты — система распознавания кассовых чеков платформы SaveFood (Казахстан).
На фото — чек списания продуктов из магазина (язык: русский/казахский).

Верни СТРОГО один JSON-объект без пояснений:
{{
  "is_receipt": true|false,
  "merchant": "название магазина или null",
  "receipt_date": "YYYY-MM-DD или null",
  "total": число или null,
  "currency": "KZT" или другая валюта, или null,
  "items": [
    {{
      "raw_name": "позиция как напечатана в чеке",
      "name": "короткое чистое название на русском",
      "category": "одна из: {', '.join(LOT_CATEGORIES)}",
      "quantity": число,
      "unit": "kg" | "pcs" | "l",
      "weight_kg": оценка веса позиции в килограммах (число)
    }}
  ],
  "authenticity": "ok" | "suspicious",
  "authenticity_reason": "почему suspicious, иначе null"
}}

Правила:
- is_receipt=false, если на фото не кассовый/товарный чек.
- category — ближайшая из четырёх; напитки и бакалею относи к "Готовая еда".
- weight_kg: для штучных товаров оцени типичный вес (батон ~0.4, йогурт ~0.3).
- Непродовольственные позиции (пакеты, бытовая химия) пропускай.
- authenticity="suspicious", если чек выглядит отредактированным, нарисованным,
  сфотографированным с экрана, со «скачущими» шрифтами или замазанными полями.
"""


def parse_receipt_image(content: bytes, mime_type: str) -> Optional[Dict[str, Any]]:
    """One Vision call: OCR + normalization + classification + authenticity.

    Returns the parsed dict or None when the AI stage is unavailable/failed
    (no key, network error, unparseable answer).
    """
    if not GEMINI_API_KEY or not content:
        return None
    try:
        with httpx.Client(timeout=60) as client:
            resp = client.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{OCR_MODEL}:generateContent",
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
                            {"text": "Распознай этот чек."},
                        ],
                    }],
                    "generationConfig": {
                        "responseMimeType": "application/json",
                        "maxOutputTokens": 4000,
                    },
                },
            )
        if resp.status_code != 200:
            logging.warning("[ocr] Gemini API %s: %s", resp.status_code, resp.text[:200])
            return None
        candidates = resp.json().get("candidates", [])
        if not candidates:
            return None
        parts = candidates[0].get("content", {}).get("parts", [])
        text = "".join(p.get("text", "") for p in parts).strip()
        # responseMimeType=application/json normally yields bare JSON, but be
        # defensive about ```json fences anyway.
        if text.startswith("```"):
            text = text.strip("`")
            if text.startswith("json"):
                text = text[4:]
        parsed = json.loads(text)
        if not isinstance(parsed, dict):
            return None
        return _sanitize(parsed)
    except Exception as e:
        logging.warning("[ocr] receipt parse failed: %s", e)
        return None


def _sanitize(parsed: Dict[str, Any]) -> Dict[str, Any]:
    """Coerce model output into the shapes the rest of the code relies on."""
    items: List[Dict[str, Any]] = []
    for it in parsed.get("items") or []:
        if not isinstance(it, dict):
            continue
        name = (it.get("name") or it.get("raw_name") or "").strip()
        if not name:
            continue
        category = it.get("category")
        if category not in LOT_CATEGORIES:
            category = "Готовая еда"
        try:
            weight = max(0.0, float(it.get("weight_kg") or 0))
        except (TypeError, ValueError):
            weight = 0.0
        items.append({
            "raw_name": (it.get("raw_name") or name).strip(),
            "name": name,
            "category": category,
            "quantity": it.get("quantity"),
            "unit": it.get("unit") if it.get("unit") in ("kg", "pcs", "l") else "pcs",
            "weight_kg": round(weight, 2),
        })
    receipt_date = None
    raw_date = parsed.get("receipt_date")
    if raw_date:
        try:
            receipt_date = date.fromisoformat(str(raw_date)[:10])
        except ValueError:
            receipt_date = None
    total = None
    try:
        if parsed.get("total") is not None:
            total = float(parsed["total"])
    except (TypeError, ValueError):
        total = None
    return {
        "is_receipt": bool(parsed.get("is_receipt")),
        "merchant": (parsed.get("merchant") or None),
        "receipt_date": receipt_date,
        "total": total,
        "currency": parsed.get("currency") or None,
        "items": items,
        "authenticity": "suspicious" if parsed.get("authenticity") == "suspicious" else "ok",
        "authenticity_reason": parsed.get("authenticity_reason") or None,
    }


def sha256_hex(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def fingerprint(parsed: Dict[str, Any]) -> Optional[str]:
    """merchant|date|total — catches the same receipt re-photographed."""
    if not parsed.get("receipt_date") or parsed.get("total") is None:
        return None
    merchant = (parsed.get("merchant") or "").strip().lower()
    return f"{merchant}|{parsed['receipt_date'].isoformat()}|{parsed['total']:.2f}"


def evaluate_fraud(parsed: Dict[str, Any], fingerprint_dupe: bool) -> Dict[str, Any]:
    """Deterministic scoring on top of the AI verdict.

    Cheap rules first (date sanity, duplicates) — they catch most abuse;
    the AI authenticity opinion only adds weight, it never decides alone.
    """
    score = 0.0
    reasons: List[str] = []
    today = datetime.now(timezone.utc).date()

    rdate = parsed.get("receipt_date")
    if rdate is None:
        score += 0.3
        reasons.append("дата чека не распознана")
    elif rdate > today:
        score += 0.8
        reasons.append(f"дата чека в будущем ({rdate.isoformat()})")
    elif rdate < today - timedelta(hours=RECEIPT_MAX_AGE_HOURS):
        score += 0.8
        reasons.append(f"чек старше {RECEIPT_MAX_AGE_HOURS} ч ({rdate.isoformat()})")

    if fingerprint_dupe:
        score += 0.9
        reasons.append("дубликат: чек с тем же магазином, датой и суммой уже загружался")

    if parsed.get("authenticity") == "suspicious":
        score += 0.5
        reason = parsed.get("authenticity_reason") or "признаки редактирования изображения"
        reasons.append(f"ИИ: {reason}")

    score = min(1.0, round(score, 2))
    return {
        "score": score,
        "reasons": reasons,
        "rejected": score >= FRAUD_REJECT_THRESHOLD,
        "flagged": score >= FRAUD_FLAG_THRESHOLD,
    }


def suggest_lots(items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Group parsed items by category into lot drafts the shop can confirm.

    One lot per category (not per line item) — a 15-line receipt must not
    flood the map with 15 tiny lots.
    """
    groups: Dict[str, Dict[str, Any]] = {}
    for it in items:
        g = groups.setdefault(it["category"], {"names": [], "weight": 0.0})
        if it["name"] not in g["names"]:
            g["names"].append(it["name"])
        g["weight"] += it.get("weight_kg") or 0.0
    drafts = []
    for category, g in groups.items():
        # lots.quantity is integer kg; never suggest 0 for a non-empty group.
        quantity = max(1, round(g["weight"]))
        drafts.append({
            "category": category,
            "description": ", ".join(g["names"])[:300],
            "quantity": quantity,
        })
    return drafts
