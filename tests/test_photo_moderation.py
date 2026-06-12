"""Delivery photo moderation: AI verdict scoring + auto-moderation decision.

Pure functions only (no Gemini call, no DB) — same style as test_kyc_auto.py.
"""
from backend.photo_moderation import score_photo, decide


def _food(**over):
    base = {"is_food": True, "depicts": "продукты", "inappropriate": False,
            "inappropriate_reason": None, "quality_ok": True, "summary": "ok"}
    base.update(over)
    return base


# ── scoring ──────────────────────────────────────────────────────────────────

def test_clear_food_scores_high():
    r = score_photo(_food())
    assert r["verdict"] == "food"
    assert r["score"] >= 0.7
    assert not r["inappropriate"]


def test_not_food_scores_low():
    r = score_photo(_food(is_food=False, depicts="кот"))
    assert r["verdict"] == "not_food"
    assert r["score"] <= 0.3


def test_inappropriate_is_hard_veto_regardless_of_food():
    r = score_photo(_food(is_food=True, inappropriate=True,
                          inappropriate_reason="оскорбительный текст"))
    assert r["verdict"] == "inappropriate"
    assert r["score"] == 0.0
    assert r["inappropriate"]


def test_low_quality_food_below_auto_approve_bar():
    # The quality penalty keeps the score under the 0.85 auto-approve bar, so a
    # blurry shot waits for a human even with auto-moderation on.
    r = score_photo(_food(quality_ok=False))
    assert r["score"] < 0.85
    assert decide(r, enabled=True, threshold=0.85) is None


# ── auto-moderation decision ───────────────────────────────────────────────────

def test_disabled_always_queues():
    r = score_photo(_food())
    assert decide(r, enabled=False, threshold=0.85) is None


def test_confident_food_auto_approves():
    r = score_photo(_food())
    assert decide(r, enabled=True, threshold=0.85) == "approved"


def test_review_does_not_auto_approve():
    r = score_photo(_food(quality_ok=False))
    assert decide(r, enabled=True, threshold=0.85) is None


def test_inappropriate_auto_rejects():
    r = score_photo(_food(inappropriate=True, inappropriate_reason="насилие"))
    assert decide(r, enabled=True, threshold=0.85) == "rejected"


def test_not_food_stays_in_queue_not_auto_rejected():
    # Only *inappropriate* is auto-rejected; plain "not food" waits for a human.
    r = score_photo(_food(is_food=False))
    assert decide(r, enabled=True, threshold=0.85) is None
