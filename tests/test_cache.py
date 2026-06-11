"""Redis cache layer: without REDIS_URL everything must be a clean no-op
(dev/tests need no Redis), and cached_json must fall through to the producer."""
from backend import cache


def test_unconfigured_is_noop():
    assert not cache.is_configured()
    assert cache.get_json("any:key") is None
    cache.set_json("any:key", {"a": 1}, ttl=10)  # must not raise
    cache.delete("any:key")  # must not raise


def test_cached_json_falls_through_to_producer():
    calls = []

    def producer():
        calls.append(1)
        return {"value": 42}

    out = cache.cached_json("k", 10, producer)
    assert out == {"value": 42}
    assert len(calls) == 1
    # without Redis nothing is stored — the producer runs again
    out2 = cache.cached_json("k", 10, producer)
    assert out2 == {"value": 42}
    assert len(calls) == 2


def test_bad_redis_url_degrades_to_noop(monkeypatch):
    monkeypatch.setattr(cache, "REDIS_URL", "not-a-valid-url://")
    monkeypatch.setattr(cache, "_client", None)
    monkeypatch.setattr(cache, "_client_failed", False)
    # init may fail lazily on first use — either way: miss, no exception
    assert cache.get_json("k") is None
    cache.set_json("k", 1, 5)
