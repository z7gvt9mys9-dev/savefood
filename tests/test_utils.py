from datetime import datetime, timedelta, timezone

from backend.utils import (
    calculate_priority_score,
    ensure_aware_utc,
    haversine,
    is_within_weekly_limit,
)


def test_haversine_known_distance():
    # Almaty centre → ~1 degree of latitude ≈ 111 km
    d = haversine(43.0, 76.9, 44.0, 76.9)
    assert 110_000 < d < 112_000


def test_haversine_zero():
    assert haversine(43.25, 76.95, 43.25, 76.95) == 0


def test_priority_score_family_and_urgency():
    base = calculate_priority_score(1, False, datetime.now(timezone.utc))
    bigger_family = calculate_priority_score(4, False, datetime.now(timezone.utc))
    urgent = calculate_priority_score(1, True, datetime.now(timezone.utc))
    assert bigger_family == base + 30
    assert urgent == base + 50


def test_priority_score_never_helped_bonus():
    helped_today = calculate_priority_score(1, False, datetime.now(timezone.utc))
    never_helped = calculate_priority_score(1, False, None)
    assert never_helped == helped_today + 100


def test_weekly_limit():
    now = datetime.now(timezone.utc)
    assert is_within_weekly_limit(None) is True
    assert is_within_weekly_limit(now - timedelta(days=8)) is True
    assert is_within_weekly_limit(now - timedelta(days=2)) is False


def test_ensure_aware_utc():
    naive = datetime(2026, 1, 1, 12, 0)
    aware = ensure_aware_utc(naive)
    assert aware.tzinfo is timezone.utc
    assert ensure_aware_utc(None) is None
    already = datetime(2026, 1, 1, tzinfo=timezone.utc)
    assert ensure_aware_utc(already) is already
