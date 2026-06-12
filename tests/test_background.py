"""Background task scheduling config (the ticks themselves are DB-bound and
covered by the runtime smoke — here we pin the mode contract and task table)."""
from backend import background


def test_default_mode_is_embedded(monkeypatch):
    monkeypatch.delenv("BACKGROUND_TASKS", raising=False)
    assert background.get_mode() == "embedded"


def test_modes_parse(monkeypatch):
    for value, expected in [
        ("external", "external"),
        ("OFF", "off"),
        ("  Embedded ", "embedded"),
        ("garbage", "embedded"),  # unknown value falls back to safe default
    ]:
        monkeypatch.setenv("BACKGROUND_TASKS", value)
        assert background.get_mode() == expected


def test_task_table_complete():
    names = [name for name, _, _ in background.TASKS]
    assert names == ["expire", "reassign", "antifraud", "reservation_ttl"]
    for _, tick, interval in background.TASKS:
        assert callable(tick)
        assert interval > 0
    # anti-fraud must run noticeably more often than the route timeout check
    assert background.ANTIFRAUD_INTERVAL < background.REASSIGN_INTERVAL
