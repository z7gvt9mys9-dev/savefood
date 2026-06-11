"""Redis cache for the hottest read paths (lots list, public stats, volunteer
locations). Postgres stays the source of truth; everything here is a
short-TTL read-through layer.

Env-gated and failure-proof by design: without REDIS_URL every helper is a
no-op (dev needs nothing), and any Redis error degrades to a cache miss —
the platform must never go down because the cache did.
"""
import json
import logging
import os
from typing import Any, Callable, Optional

REDIS_URL = os.getenv("REDIS_URL", "")

# Default TTLs (seconds). Short on purpose: staleness on the lots map must be
# bounded by seconds, so no invalidation bookkeeping is needed on writes.
TTL_LOTS = 10
TTL_STATS = 15
TTL_LOCATION = 60

_client = None
_client_failed = False


def is_configured() -> bool:
    return bool(REDIS_URL)


def _get_client():
    global _client, _client_failed
    if not REDIS_URL or _client_failed:
        return None
    if _client is None:
        try:
            import redis
            _client = redis.Redis.from_url(
                REDIS_URL,
                socket_timeout=2,
                socket_connect_timeout=2,
                decode_responses=True,
            )
        except Exception as e:  # bad URL / missing lib → permanent no-op
            logging.warning("[cache] redis client init failed: %s", e)
            _client_failed = True
            return None
    return _client


def get_json(key: str) -> Optional[Any]:
    client = _get_client()
    if client is None:
        return None
    try:
        raw = client.get(key)
        return json.loads(raw) if raw is not None else None
    except Exception as e:
        logging.debug("[cache] get %s failed: %s", key, e)
        return None


def set_json(key: str, value: Any, ttl: int):
    client = _get_client()
    if client is None:
        return
    try:
        # default=str: rows carry datetime/date — ISO strings are exactly what
        # the JSON API responses contain anyway.
        client.setex(key, ttl, json.dumps(value, ensure_ascii=False, default=str))
    except Exception as e:
        logging.debug("[cache] set %s failed: %s", key, e)


def delete(*keys: str):
    client = _get_client()
    if client is None or not keys:
        return
    try:
        client.delete(*keys)
    except Exception:
        pass


def cached_json(key: str, ttl: int, producer: Callable[[], Any]) -> Any:
    """Read-through: cache hit → cached value, miss → produce + store."""
    hit = get_json(key)
    if hit is not None:
        return hit
    value = producer()
    if value is not None:
        set_json(key, value, ttl)
    return value
