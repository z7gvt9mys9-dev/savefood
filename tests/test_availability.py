from datetime import datetime

from backend.volunteer.db import is_available_now


# A fixed Wednesday 19:30 local (weekday() == 2).
WED_1930 = datetime(2026, 6, 10, 19, 30)


def test_empty_calendar_means_always_available():
    assert is_available_now(None, now=WED_1930)
    assert is_available_now([], now=WED_1930)
    assert is_available_now("", now=WED_1930)


def test_window_match_same_day():
    avail = [{"day": 2, "start": "18:00", "end": "21:00"}]
    assert is_available_now(avail, now=WED_1930)


def test_outside_window_not_available():
    avail = [{"day": 2, "start": "08:00", "end": "12:00"}]
    assert not is_available_now(avail, now=WED_1930)


def test_wrong_weekday_not_available():
    avail = [{"day": 5, "start": "18:00", "end": "21:00"}]  # Saturday
    assert not is_available_now(avail, now=WED_1930)


def test_overnight_window():
    avail = [{"day": 2, "start": "22:00", "end": "02:00"}]
    assert is_available_now(avail, now=datetime(2026, 6, 10, 23, 30))
    assert is_available_now(avail, now=datetime(2026, 6, 10, 1, 0))
    assert not is_available_now(avail, now=datetime(2026, 6, 10, 12, 0))


def test_json_string_input_is_parsed():
    assert is_available_now('[{"day":2,"start":"18:00","end":"21:00"}]', now=WED_1930)


def test_malformed_window_is_skipped_not_crashes():
    avail = [{"day": 2, "start": "bad"}, {"day": 2, "start": "18:00", "end": "21:00"}]
    assert is_available_now(avail, now=WED_1930)
