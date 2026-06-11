"""Observability: Sentry error tracking + Prometheus metrics.

Both are env-gated no-ops when unconfigured, so dev/local runs need nothing:
- SENTRY_DSN          → enables sentry-sdk (FastAPI integration is automatic)
- METRICS_TOKEN       → if set, GET /metrics requires ?token= or Bearer token
                        (the endpoint is intentionally NOT proxied by nginx —
                        scrape it from inside the docker network)

HTTP metrics use the matched route template (`/lots/{lot_id}`), not the raw
path — otherwise every id would mint a new label value and blow up cardinality.
"""
import os
import time

from prometheus_client import (
    CONTENT_TYPE_LATEST,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

from backend.database import get_db_cursor

SENTRY_DSN = os.getenv("SENTRY_DSN", "")
METRICS_TOKEN = os.getenv("METRICS_TOKEN", "")

HTTP_REQUESTS = Counter(
    "savefood_http_requests_total",
    "HTTP requests",
    ["method", "route", "status"],
)
HTTP_LATENCY = Histogram(
    "savefood_http_request_seconds",
    "HTTP request latency",
    ["method", "route"],
    buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10),
)

ACTIVE_LOTS = Gauge("savefood_active_lots", "Lots currently on the map")
OPEN_TICKETS = Gauge("savefood_open_tickets", "Tickets waiting for a volunteer")
ROUTES_IN_PROGRESS = Gauge("savefood_routes_in_progress", "Active delivery routes")
PENDING_MODERATION = Gauge("savefood_pending_moderation", "Needy waiting for moderation")


def init_sentry() -> bool:
    if not SENTRY_DSN:
        return False
    import sentry_sdk

    sentry_sdk.init(
        dsn=SENTRY_DSN,
        environment=os.getenv("SENTRY_ENV", "production"),
        traces_sample_rate=float(os.getenv("SENTRY_TRACES_RATE", "0")),
        send_default_pii=False,  # never ship user data with events
    )
    return True


def observe_request(method: str, route: str, status: int, started: float):
    HTTP_REQUESTS.labels(method=method, route=route, status=str(status)).inc()
    HTTP_LATENCY.labels(method=method, route=route).observe(time.perf_counter() - started)


def refresh_business_gauges():
    """Called on every scrape — four cheap indexed COUNTs."""
    with get_db_cursor() as cur:
        cur.execute("SELECT COUNT(*) AS n FROM lots WHERE status = 'active'")
        ACTIVE_LOTS.set(cur.fetchone()["n"])
        cur.execute("SELECT COUNT(*) AS n FROM tickets WHERE status = 'open'")
        OPEN_TICKETS.set(cur.fetchone()["n"])
        cur.execute("SELECT COUNT(*) AS n FROM volunteer_routes WHERE status = 'in_progress'")
        ROUTES_IN_PROGRESS.set(cur.fetchone()["n"])
        cur.execute("SELECT COUNT(*) AS n FROM needy WHERE status = 'pending'")
        PENDING_MODERATION.set(cur.fetchone()["n"])


def metrics_payload() -> bytes:
    try:
        refresh_business_gauges()
    except Exception:
        pass  # DB hiccup must not break the scrape of HTTP metrics
    return generate_latest()


def metrics_allowed(token_from_request: str) -> bool:
    if not METRICS_TOKEN:
        return True
    return token_from_request == METRICS_TOKEN


__all__ = [
    "CONTENT_TYPE_LATEST",
    "init_sentry",
    "metrics_allowed",
    "metrics_payload",
    "observe_request",
]
