"""Fully automated KYC decision logic (§58): the verdict alone decides.

The auto-decision lives in _run_kyc_pipeline, but its boundary is set by
_finalize_score (score → verdict): >=0.7 likely_ok (→approve), <=0.3 likely_fraud
(→reject), in between review (→stay pending). These are pure and DB-free.
"""
from backend.kyc_service import (
    _finalize_score, VERDICT_OK, VERDICT_REVIEW, VERDICT_FRAUD,
)


def _verdict(score):
    return _finalize_score(score, [], {"summary": "s"})["verdict"]


def test_confident_high_score_is_approve_verdict():
    assert _verdict(0.9) == VERDICT_OK
    assert _verdict(0.7) == VERDICT_OK  # at the OK threshold


def test_confident_low_score_is_reject_verdict():
    assert _verdict(0.2) == VERDICT_FRAUD
    assert _verdict(0.3) == VERDICT_FRAUD  # at the fraud threshold


def test_middle_score_stays_for_retry_review():
    assert _verdict(0.5) == VERDICT_REVIEW
    assert _verdict(0.69) == VERDICT_REVIEW


def test_score_is_clamped():
    assert _finalize_score(5.0, [], {})["score"] == 1.0
    assert _finalize_score(-5.0, [], {})["score"] == 0.0
