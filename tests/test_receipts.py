"""Receipt anti-fraud scoring and lot drafting (§32.2–32.3) — pure logic only."""
from datetime import date, datetime, timedelta, timezone

from backend.receipt_service import (
    FRAUD_FLAG_THRESHOLD,
    FRAUD_REJECT_THRESHOLD,
    _sanitize,
    evaluate_fraud,
    fingerprint,
    suggest_lots,
)


def _parsed(**over):
    base = {
        "receipt_date": datetime.now(timezone.utc).date(),
        "merchant": "Magnum",
        "total": 1234.0,
        "authenticity": "ok",
        "authenticity_reason": None,
    }
    base.update(over)
    return base


def test_clean_receipt_passes():
    fraud = evaluate_fraud(_parsed(), fingerprint_dupe=False)
    assert fraud["score"] == 0.0
    assert not fraud["rejected"] and not fraud["flagged"]


def test_missing_date_is_soft_signal():
    fraud = evaluate_fraud(_parsed(receipt_date=None), fingerprint_dupe=False)
    assert fraud["score"] == 0.3
    assert not fraud["rejected"]


def test_future_date_rejects():
    future = datetime.now(timezone.utc).date() + timedelta(days=2)
    fraud = evaluate_fraud(_parsed(receipt_date=future), fingerprint_dupe=False)
    assert fraud["rejected"]


def test_stale_date_rejects():
    old = datetime.now(timezone.utc).date() - timedelta(days=10)
    fraud = evaluate_fraud(_parsed(receipt_date=old), fingerprint_dupe=False)
    assert fraud["rejected"]


def test_fingerprint_duplicate_rejects():
    fraud = evaluate_fraud(_parsed(), fingerprint_dupe=True)
    assert fraud["score"] >= FRAUD_REJECT_THRESHOLD
    assert fraud["rejected"]


def test_ai_suspicion_alone_only_flags():
    fraud = evaluate_fraud(_parsed(authenticity="suspicious"), fingerprint_dupe=False)
    assert FRAUD_FLAG_THRESHOLD <= fraud["score"] < FRAUD_REJECT_THRESHOLD
    assert fraud["flagged"] and not fraud["rejected"]


def test_score_capped_at_one():
    old = datetime.now(timezone.utc).date() - timedelta(days=10)
    fraud = evaluate_fraud(
        _parsed(receipt_date=old, authenticity="suspicious"), fingerprint_dupe=True
    )
    assert fraud["score"] == 1.0


def test_fingerprint_requires_date_and_total():
    assert fingerprint(_parsed(receipt_date=None)) is None
    assert fingerprint(_parsed(total=None)) is None
    fp = fingerprint(_parsed(receipt_date=date(2026, 6, 10), total=500.0))
    assert fp == "magnum|2026-06-10|500.00"


def test_suggest_lots_groups_by_category():
    items = [
        {"name": "Батон", "category": "Выпечка", "weight_kg": 0.4},
        {"name": "Круассан", "category": "Выпечка", "weight_kg": 0.3},
        {"name": "Кефир", "category": "Молочные продукты", "weight_kg": 1.0},
    ]
    drafts = suggest_lots(items)
    assert len(drafts) == 2  # 3 line items → 2 lots, not 3
    bakery = next(d for d in drafts if d["category"] == "Выпечка")
    assert "Батон" in bakery["description"] and "Круассан" in bakery["description"]
    assert bakery["quantity"] >= 1  # integer kg, never 0 for a non-empty group


def test_sanitize_coerces_model_output():
    parsed = _sanitize({
        "is_receipt": True,
        "receipt_date": "2026-06-10T00:00:00",
        "total": "1500",
        "items": [
            {"name": "Хлеб", "category": "Выпечка", "weight_kg": "0.4", "unit": "pcs"},
            {"name": "", "category": "Выпечка"},          # dropped: no name
            {"name": "Сок", "category": "Напитки", "weight_kg": -1},  # unknown cat → fallback
        ],
        "authenticity": "definitely-fine",  # unknown value → "ok"
    })
    assert parsed["receipt_date"] == date(2026, 6, 10)
    assert parsed["total"] == 1500.0
    assert len(parsed["items"]) == 2
    assert parsed["items"][1]["category"] == "Готовая еда"
    assert parsed["items"][1]["weight_kg"] == 0.0  # negative clamped
    assert parsed["authenticity"] == "ok"
