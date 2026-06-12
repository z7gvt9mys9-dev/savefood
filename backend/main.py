from fastapi import FastAPI, Request
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response

from backend import monitoring

# Sentry must initialize before the app object so its auto-instrumentation
# wraps FastAPI/HTTPX. No DSN in env → clean no-op.
monitoring.init_sentry()
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from backend.limiter import limiter
import os

from backend.shop import db, routes as shop_routes
from backend.needy import db as needy_db, routes as needy_routes
from backend.volunteer import db as vol_db, routes as vol_routes
from backend import auth_routes, oauth_routes, background, database, telegram_routes, proxy_service
from backend import impact as impact_routes
from backend import push_routes, partner_api, chat_routes
from backend.admin import routes as admin_routes
from backend.database import get_db_cursor

app = FastAPI(title="SaveFood - Backend")
app.state.limiter = limiter


@app.middleware("http")
async def prometheus_middleware(request: Request, call_next):
    import time as _time
    started = _time.perf_counter()
    response = await call_next(request)
    # Route template ("/lots/{lot_id}"), not the raw path — keeps label
    # cardinality bounded. Unmatched paths (404 spam) are bucketed together.
    route = request.scope.get("route")
    route_path = getattr(route, "path", "<unmatched>")
    try:
        monitoring.observe_request(request.method, route_path, response.status_code, started)
    except Exception:
        pass
    return response
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

allowed_origins = [o.strip() for o in os.getenv("CORS_ORIGIN", "").split(",") if o.strip()]
if not allowed_origins:
    raise RuntimeError(
        "CORS_ORIGIN must be set to a comma-separated list of allowed origins "
        "(e.g. https://savefood.example.com). Wildcards are not allowed with credentials."
    )
app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)
# ensure upload directories exist before mounting static files
os.makedirs(shop_routes.UPLOAD_DIR, exist_ok=True)
os.makedirs(shop_routes.RECEIPT_DIR, exist_ok=True)
os.makedirs(needy_routes.UPLOAD_DIR, exist_ok=True)
os.makedirs(vol_routes.UPLOAD_DIR, exist_ok=True)

@app.on_event("startup")
async def startup():
    database.init_common_db()
    db.init_db()
    needy_db.init_db()
    vol_db.init_db()
    database.init_ticket_extensions()
    proxy_service.start_proxy()
    await telegram_routes.start_polling()

    # Maintenance loops (expiry, route timeouts, GPS anti-fraud §27) live in
    # backend/background.py. embedded (default) = daemon threads here;
    # external = the docker-compose `worker` service runs them; off = nobody.
    if background.get_mode() == "embedded":
        background.start_threads()


@app.on_event("shutdown")
async def shutdown():
    proxy_service.stop_proxy()
    await telegram_routes.stop_polling()

app.mount("/uploads", StaticFiles(directory=shop_routes.UPLOAD_DIR), name="uploads")
# /needy_uploads is intentionally NOT mounted publicly — it contains personal
# documents (passports/social-status proofs). Downloads go through the
# auth-checked /needy/{needy_id}/document endpoint instead.
app.mount("/volunteer_uploads", StaticFiles(directory=vol_routes.UPLOAD_DIR), name="volunteer_uploads")
app.include_router(auth_routes.router)
app.include_router(oauth_routes.router)
app.include_router(shop_routes.router)
app.include_router(needy_routes.router)
app.include_router(vol_routes.router)
app.include_router(admin_routes.router)
app.include_router(telegram_routes.router)
app.include_router(impact_routes.router)
app.include_router(push_routes.router)
app.include_router(partner_api.router)
app.include_router(partner_api.manage_router)
app.include_router(chat_routes.router)


@app.get("/metrics")
def metrics(request: Request):
    """Prometheus scrape endpoint. Not exposed through nginx — scrape the
    backend container directly. Optional METRICS_TOKEN guards direct access."""
    token = request.query_params.get("token", "")
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        token = token or auth[7:]
    if not monitoring.metrics_allowed(token):
        return JSONResponse(status_code=403, content={"detail": "Forbidden"})
    return Response(content=monitoring.metrics_payload(), media_type=monitoring.CONTENT_TYPE_LATEST)


@app.get("/stats")
def stats():
    # Public landing-page counters — polled by every visitor, cached briefly.
    from backend import cache
    return cache.cached_json("stats:public", cache.TTL_STATS, _compute_stats)


def _compute_stats():
    with get_db_cursor() as cur:
        cur.execute("SELECT COALESCE(SUM(quantity),0) as kg_saved FROM lots WHERE status IN ('taken','confirmed')")
        kg_saved = cur.fetchone()['kg_saved']

        cur.execute("SELECT COUNT(*) as count FROM tickets WHERE status = 'fulfilled'")
        deliveries_completed = cur.fetchone()['count']

        cur.execute("SELECT COUNT(DISTINCT volunteer_id) as count FROM volunteer_routes WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'")
        active_volunteers = cur.fetchone()['count']

        cur.execute("SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60) as avg_min FROM volunteer_routes WHERE status = 'finished' AND finished_at IS NOT NULL")
        avg_delivery_minutes = cur.fetchone()['avg_min'] or 0

        cur.execute("SELECT COUNT(*) as total FROM lots")
        total_lots = cur.fetchone()['total'] or 0
        cur.execute("SELECT COUNT(*) as expired FROM lots WHERE status = 'expired'")
        expired_lots = cur.fetchone()['expired'] or 0
        percent_expired = (expired_lots / total_lots * 100.0) if total_lots > 0 else 0.0

    return {
        'kg_food_saved': kg_saved,
        'deliveries_completed': deliveries_completed,
        'active_volunteers': active_volunteers,
        'avg_delivery_minutes': avg_delivery_minutes,
        'percent_expired_lots': percent_expired,
    }

