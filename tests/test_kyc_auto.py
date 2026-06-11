"""Auto-KYC v2 decision logic: only confident likely_ok skips the human."""
from backend.kyc_service import should_auto_approve


def test_disabled_never_approves():
    assert not should_auto_approve("likely_ok", 1.0, enabled=False, threshold=0.85)


def test_confident_ok_approves():
    assert should_auto_approve("likely_ok", 0.9, enabled=True, threshold=0.85)
    assert should_auto_approve("likely_ok", 0.85, enabled=True, threshold=0.85)


def test_below_threshold_stays_with_human():
    assert not should_auto_approve("likely_ok", 0.84, enabled=True, threshold=0.85)


def test_review_and_fraud_never_auto_approved():
    assert not should_auto_approve("review", 0.99, enabled=True, threshold=0.85)
    assert not should_auto_approve("likely_fraud", 0.99, enabled=True, threshold=0.85)
    assert not should_auto_approve("unchecked", 0.99, enabled=True, threshold=0.85)


def test_missing_score_never_auto_approved():
    assert not should_auto_approve("likely_ok", None, enabled=True, threshold=0.85)
