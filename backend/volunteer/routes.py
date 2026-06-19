from fastapi import APIRouter, HTTPException, Depends, Request, UploadFile, File, BackgroundTasks
import html
import json
import math
import logging
import os
import psycopg2.errors

from backend.database import get_db_cursor, create_user
from backend.volunteer import db as vdb, schemas as vschemas
from backend.needy import db as needydb
from backend.utils import ensure_aware_utc, build_qr_code, coarsen_coord, validate_and_save_upload, UploadValidationError, clamp
from backend.auth import get_password_hash, get_current_user, ensure_owner_or_admin
from backend.gamification import compute_level
from backend.limiter import limiter
from backend import telegram_service, kyc_service, kyc_crypto
from datetime import datetime, timezone, timedelta
from datetime import time as dtime

try:
    from zoneinfo import ZoneInfo
except ImportError:  # py < 3.9 fallback
    ZoneInfo = None

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)
# Identity documents (§58) live in a SEPARATE dir that is NOT mounted as static
# (unlike UPLOAD_DIR → /volunteer_uploads). They hold personal data and are
# served only through the auth-checked download endpoint, then deleted after
# moderation — same handling as needy documents (§5).
KYC_UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "kyc_uploads")
os.makedirs(KYC_UPLOAD_DIR, exist_ok=True)

# Only an 'approved' volunteer may claim routes (§58). Toggle off for trusted
# deployments / local dev where verification is handled out of band.
VOLUNTEER_KYC_REQUIRED = os.getenv("VOLUNTEER_KYC_REQUIRED", "true").lower() in ("1", "true", "yes")

# Server-side delivery verification thresholds (§13)
GPS_RADIUS_METERS = 100
# Shop pickup zone is wider: building footprint + parking, not a doorway.
SHOP_GPS_RADIUS_METERS = 150
# Payload coordinates are client-supplied; cross-check them against the latest
# geolocation ping (§27). A fresh ping a kilometre away from where the client
# *claims* to stand means the coordinates are fabricated.
PING_FRESH_SECONDS = 10 * 60
PING_MISMATCH_METERS = 1000
MAX_DELIVERY_ATTEMPTS = 3  # §8: after 3 attempts the ticket is released
# §59/Q1-A: a delivery window gates SELECTION, but against a look-ahead horizon
# (the volunteer reaches the door later than the claim), not "this exact minute".
DELIVERY_WINDOW_HORIZON_MIN = 120
# §59/Q2 (light layer, no lot split): the volunteer carries the WHOLE lot, so with
# no client-supplied max_stops we serve every reserved in-window recipient up to
# this cap instead of shedding everyone past a fixed 10 (the surplus would ride
# along undelivered). The cap bounds the O(n²) 2-opt and keeps the run doable.
ROUTE_HARD_CAP = 20
LOCAL_TZ_NAME = os.getenv("LOCAL_TZ", "Asia/Almaty")

router = APIRouter()


def _verify_position_against_pings(volunteer_id: int, lat: float, lon: float):
    """Reject payload coordinates that contradict the volunteer's last
    geolocation ping. Only a FRESH ping is compared — punishing honest
    volunteers whose tracker went quiet an hour ago would be worse than
    the fraud this catches. No ping history → nothing to compare."""
    loc = vdb.get_volunteer_location(volunteer_id)
    if not loc or loc.get("lat") is None or loc.get("lon") is None or not loc.get("updated_at"):
        return
    try:
        age = (datetime.now(timezone.utc) - ensure_aware_utc(loc["updated_at"])).total_seconds()
    except Exception:
        return
    if age > PING_FRESH_SECONDS:
        return
    dist_m = haversine((lat, lon), (loc["lat"], loc["lon"])) * 1000.0
    if dist_m > PING_MISMATCH_METERS:
        raise HTTPException(
            status_code=400,
            detail=(
                f"Координаты подтверждения расходятся с вашей геолокацией на {int(dist_m)} м — "
                "обновите положение в приложении и попробуйте снова"
            ),
        )


def _require_route_owner(route_id: int, current_user: dict, require_active: bool = False):
    """Return the route if the authenticated volunteer owns it (or admin), else 404/403.

    With require_active=True the route must still be 'in_progress' — a timed-out or
    finished route must not be able to fulfil tickets that may already belong to
    another volunteer's route."""
    route = vdb.get_route_by_id(route_id)
    if not route:
        raise HTTPException(status_code=404, detail="Route not found")
    ensure_owner_or_admin(current_user, "volunteer", route["volunteer_id"])
    if require_active and route.get("status") != "in_progress":
        raise HTTPException(status_code=400, detail="Маршрут уже завершён или сброшен")
    return route


@router.post("/volunteers/register")
@limiter.limit("5/minute")
def register(request: Request, vol: vschemas.VolunteerCreate):
    # An account is mandatory: a volunteer row without credentials is unusable
    # (every dashboard endpoint requires a login bound to related_id).
    if not vol.username or not vol.password:
        raise HTTPException(status_code=400, detail="Укажите логин и пароль")
    hashed = get_password_hash(vol.password)
    # Single transaction: if the username is taken, the volunteer row rolls back
    # too — no orphaned volunteers on 409.
    try:
        with get_db_cursor() as cur:
            # status='pending' MUST be set explicitly (§58): a freshly registered
            # volunteer has not cleared KYC yet. Leaving it NULL would make the
            # init_db grandfather UPDATE (status IS NULL → 'approved') re-approve
            # the account on the next startup, silently bypassing the KYC gate.
            cur.execute(
                "INSERT INTO volunteers (name, contact, lat, lon, city, status, created_at) VALUES (%s, %s, %s, %s, %s, 'pending', %s) RETURNING id",
                (vol.name, vol.contact, vol.lat, vol.lon, vol.city, datetime.now(timezone.utc)),
            )
            vid = cur.fetchone()['id']
            cur.execute(
                "INSERT INTO users (username, hashed_password, role, related_id) VALUES (%s, %s, %s, %s)",
                (vol.username, hashed, "volunteer", vid),
            )
    except psycopg2.errors.UniqueViolation:
        raise HTTPException(status_code=409, detail="Username already taken")
    return {"id": vid}


@router.post("/volunteers/{volunteer_id}/document/upload")
@limiter.limit("10/minute")
def upload_volunteer_document(volunteer_id: int, request: Request, file: UploadFile = File(...), current_user: dict = Depends(get_current_user)):
    """Upload an identity document for KYC (§58). Fires the AI pre-check; the
    verdict lands in the admin moderation queue. The file is stored outside the
    public static dir and removed once a moderator/auto-KYC decides."""
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    vol = vdb.get_volunteer_by_id(volunteer_id)
    if not vol:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    try:
        filename = validate_and_save_upload(file, KYC_UPLOAD_DIR, allow_pdf=True)
    except UploadValidationError as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.detail)
    # Encrypt the identity document at rest immediately (§58): on disk it is only
    # ever ciphertext; the AI verification decrypts it in memory.
    kyc_crypto.encrypt_file(os.path.join(KYC_UPLOAD_DIR, filename))
    vdb.set_volunteer_document(volunteer_id, f"/volunteer_kyc/{filename}")
    # Re-checking is allowed while pending: a rejected volunteer can re-upload and
    # is moved back into the queue. An already-approved volunteer is left alone.
    if vol.get("status") == kyc_service.STATUS_REJECTED:
        vdb.set_volunteer_status(volunteer_id, kyc_service.STATUS_PENDING)
    kyc_service.start_volunteer_kyc_check(
        volunteer_id, os.path.join(KYC_UPLOAD_DIR, filename), vol.get("name") or ""
    )
    return {"ok": True, "status": "pending"}


# KYC is fully automated (§58): identity documents are verified by AI without any
# human in the loop. There is deliberately no document-download and no manual
# approve/reject endpoint — humans never access the document or make the decision.
# Documents are retained encrypted at rest for break-glass accountability only.


@router.get("/volunteers/map")
def get_map_points(current_user: dict = Depends(get_current_user)):
    if current_user.get("role") not in ("volunteer", "admin"):
        raise HTTPException(status_code=403, detail="Forbidden")
    shops_map = {}
    with get_db_cursor() as cur:
        cur.execute("""
            SELECT s.id as shop_id, s.name, s.lat, s.lon, s.kind, l.id as lot_id, l.description, l.quantity, l.photo, l.category
            FROM shops s
            JOIN lots l ON s.id = l.shop_id
            WHERE l.status = 'active' AND l.quantity > 0
            AND (l.expiry_date IS NULL OR l.expiry_date::date > CURRENT_DATE + INTERVAL '1 day')
        """)
        for r in cur.fetchall():
            if r['lat'] is None or r['lon'] is None:
                continue
            sid = r['shop_id']
            if sid not in shops_map:
                shops_map[sid] = {
                    'shop_id': sid,
                    'name': r['name'],
                    'lat': r['lat'],
                    'lon': r['lon'],
                    'kind': r.get('kind') or 'business',
                    'lots': []
                }
            shops_map[sid]['lots'].append({
                'lot_id': r['lot_id'],
                'description': r['description'],
                'quantity': r['quantity'],
                'photo': r['photo'],
                'category': r['category'],
            })

    shops = list(shops_map.values())

    tickets = []
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM tickets WHERE status = 'open' AND lat IS NOT NULL AND lon IS NOT NULL AND (self_pickup IS NULL OR self_pickup = FALSE)")
        for r in cur.fetchall():
            tickets.append({
                'ticket_id': r['id'],
                'needy_id': r['needy_id'],
                'items': r['items'],
                'available_time': r['available_time'],
                # Anyone can self-register as a volunteer, so the open-requests
                # layer must not leak exact home addresses of vulnerable people:
                # coordinates are snapped to a ~500 m grid. The precise point is
                # disclosed only in route points after start_route assigns the
                # ticket to this volunteer.
                'lat': coarsen_coord(r['lat']),
                'lon': coarsen_coord(r['lon']),
                'approx': True,
            })

    return {'shops': shops, 'tickets': tickets}

@router.get("/volunteers/{volunteer_id}", response_model=vschemas.VolunteerOut)
def get_volunteer(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    v = vdb.get_volunteer_by_id(volunteer_id)
    if not v:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    return v


@router.patch("/volunteers/{volunteer_id}")
def patch_volunteer(volunteer_id: int, payload: vschemas.VolunteerUpdate, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    availability_json = None
    if payload.availability is not None:
        # Validated windows → compact JSON for the TEXT column.
        availability_json = json.dumps([w.model_dump() for w in payload.availability])
    updated = vdb.update_volunteer(volunteer_id, payload.name, payload.contact, payload.lat, payload.lon, payload.city, has_thermal_bag=payload.has_thermal_bag, availability_json=availability_json)
    if not updated:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    return updated


def haversine(a, b):
    # a and b are (lat, lon)
    R = 6371
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = math.sin(dlat/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(dlon/2)**2
    return 2*R*math.asin(math.sqrt(h))


def _route_length_km(order, start):
    total = 0.0
    cur = start
    for t in order:
        total += haversine(cur, (t['lat'], t['lon']))
        cur = (t['lat'], t['lon'])
    return total


def _optimize_stop_order(tickets, start):
    """Order the chosen stops to minimise total travel distance (§14).

    Nearest-neighbour seed + 2-opt segment reversal until no improvement.
    The old greedy interleave of (-score, distance) produced zig-zag routes:
    priority decides WHO gets food (selection), distance decides the ORDER —
    everyone selected is served on the same trip anyway.
    With max_stops ≤ ROUTE_HARD_CAP (20) the O(n²) 2-opt passes are instant.
    """
    if len(tickets) < 2:
        return list(tickets)
    remaining = list(tickets)
    order = []
    cur = start
    while remaining:
        best = min(remaining, key=lambda t: haversine(cur, (t['lat'], t['lon'])))
        order.append(best)
        remaining.remove(best)
        cur = (best['lat'], best['lon'])

    best_len = _route_length_km(order, start)
    improved = True
    while improved:
        improved = False
        for i in range(len(order) - 1):
            for j in range(i + 1, len(order)):
                cand = order[:i] + order[i:j + 1][::-1] + order[j + 1:]
                cand_len = _route_length_km(cand, start)
                if cand_len < best_len - 1e-9:
                    order, best_len, improved = cand, cand_len, True
    return order

def is_available_now(available_time: str) -> bool:
    if not available_time:
        return True
    # expected format: "HH:MM-HH:MM" — user entered times are local (LOCAL_TZ)
    try:
        parts = available_time.split('-')
        if len(parts) != 2:
            return True
        start = parts[0].strip()
        end = parts[1].strip()
        sh, sm = [int(x) for x in start.split(':')]
        eh, em = [int(x) for x in end.split(':')]
        if ZoneInfo is not None:
            try:
                now = datetime.now(ZoneInfo(LOCAL_TZ_NAME)).time()
            except Exception:
                now = datetime.now().time()
        else:
            now = datetime.now().time()
        start_t = dtime(sh, sm)
        end_t = dtime(eh, em)
        if start_t <= end_t:
            return start_t <= now <= end_t
        else:
            # overnight slot
            return now >= start_t or now <= end_t
    except Exception:
        return True


def _local_now_minutes() -> int:
    """Minutes since local midnight in LOCAL_TZ (the single-region assumption,
    §59/Q8). Uses the module-level `datetime` so tests can freeze it."""
    if ZoneInfo is not None:
        try:
            now_t = datetime.now(ZoneInfo(LOCAL_TZ_NAME)).time()
        except Exception:
            now_t = datetime.now().time()
    else:
        now_t = datetime.now().time()
    return now_t.hour * 60 + now_t.minute


def window_open_within(available_time: str, horizon_minutes: int) -> bool:
    """True if the recipient's delivery window is open now OR opens within the
    next `horizon_minutes`. `available_time` is 'HH:MM-HH:MM' in LOCAL_TZ; empty
    or unparseable is treated as always-available (lenient, like is_available_now).

    Selection must not route to a door that won't open during the run, but a
    fixed look-ahead horizon — rather than "this exact minute" — keeps a working
    person whose window opens soon from being filtered out and then cancelled
    wholesale with the lot (§59/Q1-A). The horizon is fixed, not a per-stop ETA:
    the visiting order, and thus each stop's ETA, is only known after selection."""
    if is_available_now(available_time):
        return True
    if not available_time or horizon_minutes <= 0:
        return False
    try:
        start_s = available_time.split('-')[0].strip()
        sh, sm = [int(x) for x in start_s.split(':')]
    except Exception:
        return True
    delta = ((sh * 60 + sm) - _local_now_minutes()) % (24 * 60)
    return delta <= horizon_minutes


def enforce_volunteer_kyc(vol: dict, current_user: dict):
    """KYC gate (§58): an unverified volunteer must not take custody of food —
    that is the whole point of identity verification (food leaves the shop in
    their hands). Admins bypass (they act on behalf of trusted staff). Call this
    from EVERY path that hands a lot to a volunteer, so the gate cannot be
    forgotten when a new custody route is added."""
    if VOLUNTEER_KYC_REQUIRED and current_user.get("role") != "admin" and vol.get("status") != kyc_service.STATUS_APPROVED:
        raise HTTPException(
            status_code=403,
            detail="Аккаунт волонтёра ещё не верифицирован — загрузите удостоверение личности и дождитесь проверки, чтобы брать маршруты",
        )


@router.post("/volunteers/{volunteer_id}/start_route")
def start_route(volunteer_id: int, payload: vschemas.StartRouteRequest, background_tasks: BackgroundTasks = None, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    vol = vdb.get_volunteer_by_id(volunteer_id)
    if not vol:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    enforce_volunteer_kyc(vol, current_user)
    # prevent multiple active routes for the same volunteer
    existing = vdb.get_active_route(volunteer_id)
    if existing:
        raise HTTPException(status_code=400, detail="Volunteer already has an active route")
    vol_name = vol.get('name') or f"volunteer_{volunteer_id}"

    # get lot and its shop coordinates
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM lots WHERE id = %s", (payload.lot_id,))
        lot = cur.fetchone()
        if not lot:
            raise HTTPException(status_code=404, detail="Lot not found")
        cur.execute("SELECT * FROM shops WHERE id = %s", (lot['shop_id'],))
        shop = cur.fetchone()
        if not shop:
            raise HTTPException(status_code=404, detail="Shop not found")

    if shop['lat'] is None or shop['lon'] is None:
        raise HTTPException(status_code=400, detail="Shop has no coordinates")

    # Cold chain (§47): a refrigerated lot may only be carried by a volunteer
    # with a thermal bag, otherwise the food spoils en route.
    if lot.get('requires_cold') and not vol.get('has_thermal_bag'):
        raise HTTPException(
            status_code=400,
            detail="Этот лот требует холодильник — отметьте «есть термосумка» в профиле, чтобы взять его",
        )

    # collect open delivery tickets for THIS lot only (§3.2: the recipient picked
    # a concrete lot — assembling the route shop-wide would hand a dairy request
    # whatever lot the volunteer happened to claim, even an expired one).
    # Read-only here; the actual claim happens later inside the transactional block.
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT t.* FROM tickets t
            WHERE t.status = 'open'
              AND t.lat IS NOT NULL AND t.lon IS NOT NULL
              AND (t.self_pickup IS NULL OR t.self_pickup = FALSE)
              AND t.lot_id = %s
            """,
            (payload.lot_id,),
        )
        tickets = [dict(r) for r in cur.fetchall()]

    # §59/Q1-A: include recipients whose delivery window is open now OR opens
    # within DELIVERY_WINDOW_HORIZON_MIN — the volunteer reaches the door later
    # than the claim, so filtering on "exactly now" unfairly drops (and then
    # cancels, below) people with a narrow window. displaced_count then lets the
    # repeatedly-bumped climb the queue (§59/Q1-C).
    had_any = bool(tickets)
    tickets = [t for t in tickets if window_open_within(t.get('available_time'), DELIVERY_WINDOW_HORIZON_MIN)]

    # A route with zero delivery points makes no sense: the volunteer would
    # claim the lot, see only the shop pin and "finish" without a single QR
    # scan, silently burying the lot. Refuse early with a human reason.
    if not tickets:
        raise HTTPException(
            status_code=400,
            detail=(
                "Все получатели этого лота недоступны в ближайшее время (вне окна доступности) — попробуйте позже"
                if had_any else
                "На этот лот пока нет заявок на доставку — маршрут не создан"
            ),
        )

    lot_category = (lot.get('category') or '').lower()

    # keyword mapping: lot category → food-related terms to check in needy preferences
    _CAT_KEYWORDS = {
        'молочные': ['молок', 'лактоз', 'сыр', 'творог', 'кефир', 'йогурт', 'сметан'],
        'выпечка': ['глютен', 'мука', 'хлеб', 'пшениц', 'злак'],
        'мясо': ['мяс', 'свинин', 'говяд', 'курин', 'баранин'],
        'рыба': ['рыб', 'морепродукт'],
        'орехи': ['орех', 'арахис'],
    }
    _RESTRICTION_WORDS = ['аллергия', 'нельзя', 'не ем', 'без ', 'непереносимость', 'не могу', 'запрет']

    relevant_kws_for_lot = next(
        (kws for cat_key, kws in _CAT_KEYWORDS.items() if cat_key in lot_category),
        []
    )

    # Pre-load every profile once instead of re-querying inside the O(n²) greedy loop.
    profiles_cache = {}
    for t in tickets:
        nid = t['needy_id']
        if nid in profiles_cache:
            continue
        try:
            profiles_cache[nid] = needydb.get_profile(nid) or {}
        except Exception:
            profiles_cache[nid] = {}

    # Per-ticket score is invariant during the greedy loop — compute it once.
    score_cache = {}

    def compute_score(t):
        cached = score_cache.get(t['id'])
        if cached is not None:
            return cached
        profile = profiles_cache.get(t['needy_id'], {})
        try:
            age_days = 0
            created_at = t.get('created_at')
            if created_at:
                created_at = ensure_aware_utc(created_at)
                age_days = (datetime.now(timezone.utc) - created_at).days
        except Exception:
            age_days = 0
        family = profile.get('family_size') or 1
        urgency_map = {'low': 0, 'normal': 1, 'high': 3, 'critical': 5}
        urg = urgency_map.get((profile.get('urgency') or '').lower(), 1)
        last = profile.get('last_received_at')
        days_no_help = 0
        try:
            if last:
                last_dt = ensure_aware_utc(last)
                days_no_help = (datetime.now(timezone.utc) - last_dt).days
        except Exception:
            days_no_help = 0
        displaced = profile.get('displaced_count') or 0
        # §59/Q3: weights shifted from self-declared (urgency, family) toward
        # objective need (days waiting, prior displacement) so an inflated
        # 'critical' + large family can't dominate the queue; §59/Q1-C adds the
        # displacement bonus so a repeatedly-bumped recipient climbs.
        score = age_days * 1.5 + family * 1.0 + urg * 2.0 + days_no_help * 3.0 + displaced * 3.0

        # §6: match lot category against needy food preferences/restrictions.
        # Check restriction words only in the same clause as the category keyword
        # — splitting on commas/semicolons/dots — so "люблю молоко, нет аллергий"
        # is not mistaken for a restriction on dairy.
        if relevant_kws_for_lot:
            prefs = (profile.get('preferences') or '').lower()
            if prefs:
                import re as _re
                clauses = [c.strip() for c in _re.split(r'[.,;\n]+', prefs) if c.strip()]
                clauses_with_cat = [c for c in clauses if any(kw in c for kw in relevant_kws_for_lot)]
                if clauses_with_cat:
                    conflict = any(
                        any(rw in c for rw in _RESTRICTION_WORDS)
                        for c in clauses_with_cat
                    )
                    if conflict:
                        score -= 8.0
                    else:
                        score += 2.0

        score_cache[t['id']] = score
        return score

    # §14: priority decides WHO is served (selection), distance decides the
    # visiting ORDER (nearest-neighbour + 2-opt) — see _optimize_stop_order.
    # §59/Q2: the volunteer takes the whole lot, so every reserved in-window unit
    # is already in their vehicle — serve as many as fit, not a fixed 10, or the
    # surplus rides along undelivered. An omitted max_stops (the usual case — the
    # client sends none) means "as many as feasible" = ROUTE_HARD_CAP; an explicit
    # value is still honoured. Lots with more than ROUTE_HARD_CAP reserved tickets
    # still shed the overflow (rare; full multi-route split is the v3.0 answer).
    # Resolve the default with `is None` (not `or`) so an explicit 0 isn't read as
    # "serve all"; the schema's ge=1 already rejects 0/negatives, this is the floor.
    requested_stops = payload.max_stops if payload.max_stops is not None else ROUTE_HARD_CAP
    max_stops = clamp(requested_stops, 1, ROUTE_HARD_CAP)
    selected = sorted(tickets, key=lambda t: -compute_score(t))[:max_stops]
    order = _optimize_stop_order(selected, (shop['lat'], shop['lon']))

    # build points: first shop, then tickets
    points = []
    points.append({'kind':'shop','lat':shop['lat'],'lon':shop['lon'],'description':shop['name'],'ticket_id':None})
    for t in order:
        addr_parts = []
        if t.get('apartment'): addr_parts.append(f"кв. {t['apartment']}")
        if t.get('floor_num'): addr_parts.append(f"этаж {t['floor_num']}")
        if t.get('entrance'): addr_parts.append(f"подъезд {t['entrance']}")
        points.append({
            'kind': 'ticket',
            'lat': t['lat'],
            'lon': t['lon'],
            'description': t['items'],
            'ticket_id': t['id'],
            'apartment': t.get('apartment'),
            'floor_num': t.get('floor_num'),
            'entrance': t.get('entrance'),
            'addr_detail': ', '.join(addr_parts) if addr_parts else None,
        })

    # ── Single transaction: claim the lot, assign tickets, create the route.
    # On any failure the cursor rolls back, so we cannot end up with a 'taken'
    # lot dangling without a route or with half-assigned tickets.
    now = datetime.now(timezone.utc)
    try:
      with get_db_cursor() as cur:
        cur.execute(
            "UPDATE lots SET status = 'taken', taken_at = %s, taken_by = %s WHERE id = %s AND status = 'active'",
            (now, vol_name, payload.lot_id),
        )
        if cur.rowcount == 0:
            raise HTTPException(status_code=400, detail="Lot is not available")

        cur.execute(
            "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, %s, 0)",
            (lot["shop_id"], payload.lot_id, 'lot_taken',
             f'Волонтёр {vol_name} забрал лот #{payload.lot_id}', now),
        )

        # The lot physically leaves the shop with this volunteer — an open
        # self-pickup ticket on it would send its recipient to an empty shelf.
        # Cancel them now, with an explanation; their weekly window stays free.
        cur.execute(
            "UPDATE tickets SET status = 'cancelled' WHERE lot_id = %s AND status = 'open' AND self_pickup = TRUE RETURNING id, needy_id, quantity",
            (payload.lot_id,),
        )
        for row in cur.fetchall():
            # Attempt to return quantity (guarded: lot must be active).
            # Since we just set status='taken' above, this will correctly do nothing,
            # ensuring reserved units don't "resurrect" on the map.
            cur.execute(
                "UPDATE lots SET quantity = quantity + %s WHERE id = %s AND status = 'active'",
                (row['quantity'], payload.lot_id)
            )
            cur.execute(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                (row['needy_id'], 'self_pickup_cancelled',
                 f'Заявка #{row["id"]} на самовывоз отменена: лот забрал волонтёр. Выберите другой лот — лимит не потрачен.', now),
            )

        assigned_ids = set()
        assigned_needy = []  # (needy_id, ticket_id) — Telegram pings after commit
        for t in order:
            cur.execute(
                "UPDATE tickets SET status = 'assigned', assigned_volunteer_id = %s WHERE id = %s AND status = 'open'",
                (volunteer_id, t['id']),
            )
            if cur.rowcount > 0:
                assigned_ids.add(t['id'])
                # §12 volunteer_assigned: the recipient must learn that someone
                # took their request — previously this notification never existed.
                cur.execute(
                    "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                    (t['needy_id'], 'volunteer_assigned',
                     f'Волонтёр {vol_name} принял вашу заявку #{t["id"]} и скоро поедет в магазин за продуктами', now),
                )
                assigned_needy.append((t['needy_id'], t['id']))

        if not assigned_ids:
            # Every selected ticket was claimed by a competing route between our
            # SELECT and UPDATE. Raising rolls back the lot claim too.
            raise HTTPException(status_code=409, detail="Заявки уже разобраны другими волонтёрами — попробуйте другой лот")

        # The lot physically leaves with this volunteer, so any delivery ticket
        # on it that did NOT make this route (over max_stops, or its recipient
        # was outside their time window at claim) can never be served — the lot
        # is 'taken' and no second route can claim it. Left as 'open' they would
        # hang forever, locking the recipient's one-active-ticket slot and their
        # reserved unit. Cancel them now (same treatment as self-pickup above):
        # free the slot, return the unit (guarded no-op — lot is 'taken'), notify.
        cur.execute(
            "UPDATE tickets SET status = 'cancelled' WHERE lot_id = %s AND status = 'open' "
            "AND (self_pickup IS NULL OR self_pickup = FALSE) RETURNING id, needy_id, quantity",
            (payload.lot_id,),
        )
        for row in cur.fetchall():
            cur.execute(
                "UPDATE lots SET quantity = quantity + %s WHERE id = %s AND status = 'active'",
                (row['quantity'], payload.lot_id),
            )
            cur.execute(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                (row['needy_id'], 'ticket_cancelled',
                 f'Заявка #{row["id"]} отменена: лот забрал волонтёр для других получателей. '
                 f'Выберите другой лот — недельный лимит не потрачен.', now),
            )
            # §59/Q1-C: the recipient reserved and waited but was bumped (window
            # closed for the run, or over max_stops). Count it so they climb the
            # priority queue next time and the loss isn't systematic.
            cur.execute(
                "UPDATE needy_profile SET displaced_count = displaced_count + 1 WHERE needy_id = %s",
                (row['needy_id'],),
            )

        filtered_points = [p for p in points if p.get('kind') == 'shop' or (p.get('ticket_id') in assigned_ids)]
        points_json = json.dumps(filtered_points, ensure_ascii=False)

        # Distance volunteer→shop at claim time: the anti-fraud monitor compares
        # later pings against it to detect a volunteer driving away with the claim.
        start_dist_m = None
        if vol.get('lat') is not None and vol.get('lon') is not None:
            start_dist_m = haversine((vol['lat'], vol['lon']), (shop['lat'], shop['lon'])) * 1000.0

        cur.execute(
            "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at, lot_id, start_dist_m) VALUES (%s, %s, 'in_progress', %s, %s, %s) RETURNING id",
            (volunteer_id, points_json, now, payload.lot_id, start_dist_m),
        )
        route_id = cur.fetchone()['id']

        cur.execute(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
            (volunteer_id, 'route_created',
             f'Маршрут #{route_id} построен — посмотрите вкладку «Маршрут»', now),
        )
    except psycopg2.errors.UniqueViolation:
        # uq_routes_one_active_per_volunteer: a parallel start_route won the race
        # after our early check. Its transaction owns the lot and the route; this
        # one rolled back, so nothing is half-claimed.
        raise HTTPException(status_code=400, detail="Volunteer already has an active route")

    # Enterprise webhooks (ERP integration) — fire-and-forget, outside the tx.
    try:
        from backend import webhook_service
        webhook_service.fire(lot["shop_id"], "lot.taken", {
            "lot_id": payload.lot_id,
            "route_id": route_id,
            "description": lot.get("description"),
            "quantity": lot.get("quantity"),
            "volunteer_name": vol_name,
        })
    except Exception:
        pass

    # Telegram is best-effort and lives outside the transaction. Each send can
    # block up to the httpx timeout, and a route now carries up to ROUTE_HARD_CAP
    # recipients, so a degraded Telegram could otherwise add ROUTE_HARD_CAP×timeout
    # of latency to the claim. Run the whole fan-out AFTER the response as a
    # background task. Names and lot descriptions originate from user input, so
    # escape them before embedding into an HTML-parsed Telegram message.
    # `description` is a nullable column, so `.get` can't supply the fallback (the
    # key is present-but-None) — use `or` to catch both missing and NULL/empty.
    lot_desc = lot.get('description') or f'лот #{payload.lot_id}'
    safe_vol_name = html.escape(vol_name or "")
    safe_lot_desc = html.escape(lot_desc)
    # Capture only the scalar the closure needs, not the whole `lot` row: the
    # background task can outlive the response, and pinning the full row until the
    # fan-out drains is wasted retention.
    shop_id = lot['shop_id']

    def _notify_route_created():
        try:
            telegram_service.notify_shop(shop_id, f"🛒 Волонтёр <b>{safe_vol_name}</b> взял ваш лот «{safe_lot_desc}». Маршрут #{route_id} в пути.")
        except Exception:
            pass
        for needy_id, t_id in assigned_needy:
            try:
                telegram_service.notify_needy(
                    needy_id,
                    f"🚚 Волонтёр <b>{safe_vol_name}</b> принял вашу заявку #{t_id} и скоро поедет в магазин.",
                )
            except Exception:
                pass

    # No injected BackgroundTasks (e.g. a direct call in tests) ⇒ run inline.
    if background_tasks is not None:
        background_tasks.add_task(_notify_route_created)
    else:
        _notify_route_created()

    return {'route_id': route_id, 'points': filtered_points}


@router.post("/volunteers/route/{route_id}/complete_point")
def complete_point(route_id: int, payload: vschemas.CompletePointRequest, current_user: dict = Depends(get_current_user)):
    # verify route ownership against the authenticated user (not client-supplied id)
    route = _require_route_owner(route_id, current_user, require_active=True)
    route_volunteer_id = route["volunteer_id"]

    # parse points and find target point
    try:
        points = json.loads(route['points'])
    except Exception:
        points = []

    target_idx = None
    if payload.ticket_id is None:
        # complete the shop point (first shop kind that is not done)
        for i, p in enumerate(points):
            if p.get('kind') == 'shop' and not p.get('done'):
                target_idx = i
                break
    else:
        for i, p in enumerate(points):
            if p.get('kind') == 'ticket' and p.get('ticket_id') == payload.ticket_id:
                target_idx = i
                break

    if target_idx is None:
        raise HTTPException(status_code=404, detail="Point not found in route")

    # prevent double completion
    if points[target_idx].get('done'):
        raise HTTPException(status_code=400, detail="Point already completed")

    point = points[target_idx]

    # if ticket point — verify QR + GPS server-side (§13), then mark ticket fulfilled
    if point.get('kind') == 'ticket' and point.get('ticket_id'):
        # The QR carries a per-ticket secret only the recipient knows, so a
        # volunteer can't fabricate a delivery without meeting them (the route
        # points never include the secret). Read it fresh from the DB.
        with get_db_cursor() as cur:
            cur.execute("SELECT qr_secret FROM tickets WHERE id = %s", (point['ticket_id'],))
            srow = cur.fetchone()
        expected_qr = build_qr_code(point['ticket_id'], (srow or {}).get('qr_secret'))
        if (payload.qr_code or "").strip() != expected_qr:
            raise HTTPException(status_code=400, detail="QR-код не совпадает с тикетом")
        if payload.lat is None or payload.lon is None:
            raise HTTPException(status_code=400, detail="Координаты волонтёра обязательны для подтверждения доставки")
        if point.get('lat') is not None and point.get('lon') is not None:
            dist_m = haversine((payload.lat, payload.lon), (point['lat'], point['lon'])) * 1000.0
            if dist_m > GPS_RADIUS_METERS:
                raise HTTPException(
                    status_code=400,
                    detail=f"Вы слишком далеко от точки доставки ({int(dist_m)} м, лимит {GPS_RADIUS_METERS} м)",
                )
        _verify_position_against_pings(route_volunteer_id, payload.lat, payload.lon)
        with get_db_cursor() as cur:
            cur.execute("SELECT * FROM tickets WHERE id = %s", (point['ticket_id'],))
            t = cur.fetchone()
            if not t:
                raise HTTPException(status_code=404, detail="Ticket not found")
            t_row = dict(t)
            # Guard on status+owner: the reassign watchdog may have released this
            # ticket (and another volunteer claimed it) while we were validating —
            # a stale route must not fulfil someone else's assignment.
            cur.execute(
                "UPDATE tickets SET status = 'fulfilled', fulfilled_at = %s "
                "WHERE id = %s AND status = 'assigned' AND assigned_volunteer_id = %s",
                (datetime.now(timezone.utc), point['ticket_id'], route_volunteer_id),
            )
            if cur.rowcount == 0:
                raise HTTPException(
                    status_code=409,
                    detail="Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)",
                )
            try:
                needydb.set_profile_last_received(t_row['needy_id'], datetime.now(timezone.utc))
            except Exception:
                logging.exception("Failed to update last_received_at for needy %s", t_row['needy_id'])

    # if completing shop point — notify needy that volunteer is en route
    if point.get('kind') == 'shop':
        shop_lat = point.get('lat')
        shop_lon = point.get('lon')
        # «Я забрал» switches OFF the GPS drift monitor (§27: antifraud_tick
        # skips routes with a done shop point), so it must itself prove
        # presence at the shop — otherwise one tap from home both fakes the
        # pickup and disarms the antifraud for the whole route.
        if payload.lat is None or payload.lon is None:
            raise HTTPException(status_code=400, detail="Координаты волонтёра обязательны для подтверждения получения лота")
        if shop_lat is not None and shop_lon is not None:
            dist_m = haversine((payload.lat, payload.lon), (shop_lat, shop_lon)) * 1000.0
            if dist_m > SHOP_GPS_RADIUS_METERS:
                raise HTTPException(
                    status_code=400,
                    detail=f"Вы слишком далеко от магазина ({int(dist_m)} м, лимит {SHOP_GPS_RADIUS_METERS} м)",
                )
        _verify_position_against_pings(route_volunteer_id, payload.lat, payload.lon)
        # use the route's volunteer id (verified above), not the client payload
        vol = vdb.get_volunteer_by_id(route_volunteer_id)
        vol_name = vol.get('name') if vol else f"volunteer_{route_volunteer_id}"
        with get_db_cursor() as cur:
            for p in points:
                if p.get('kind') == 'ticket' and not p.get('done') and p.get('ticket_id'):
                    # fetch ticket to get needy_id and current status
                    cur.execute("SELECT * FROM tickets WHERE id = %s", (p.get('ticket_id'),))
                    t = cur.fetchone()
                    if not t:
                        continue
                    # only notify if ticket is still open or already assigned
                    if t['status'] not in ('open','assigned'):
                        continue
                    needy_id = t['needy_id']
                    eta_text = ""
                    if shop_lat and shop_lon and p.get('lat') and p.get('lon'):
                        dist_km = haversine((shop_lat, shop_lon), (p['lat'], p['lon']))
                        eta_min = max(5, int(dist_km / 30 * 60) + 5)
                        arrival = datetime.now(timezone.utc) + timedelta(minutes=eta_min)
                        arrival_end = arrival + timedelta(minutes=30)
                        eta_text = f" с {arrival.strftime('%H:%M')} до {arrival_end.strftime('%H:%M')}"
                    payload_text = f"Волонтёр {vol_name} едет к вам (тикет {p.get('ticket_id')}). Ожидаемое время прибытия{eta_text}."
                    cur.execute(
                        "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                        (needy_id, 'volunteer_en_route', payload_text, datetime.now(timezone.utc)),
                    )
                    try:
                        # vol_name is user-supplied — escape before HTML parse_mode.
                        safe_payload = (
                            f"Волонтёр {html.escape(vol_name or '')} едет к вам "
                            f"(тикет {p.get('ticket_id')}). Ожидаемое время прибытия{eta_text}."
                        )
                        telegram_service.notify_needy(needy_id, f"🚗 {safe_payload}")
                    except Exception:
                        pass

    # mark point as done and persist
    points[target_idx]['done'] = True
    vdb.update_route_points(route_id, json.dumps(points, ensure_ascii=False))

    return {'ok': True}


@router.get("/volunteers/{volunteer_id}/history")
def history(volunteer_id: int, limit: int = 20, offset: int = 0, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    routes = vdb.get_routes_by_volunteer(volunteer_id, limit=limit, offset=offset)
    for r in routes:
        try:
            r['points'] = json.loads(r['points'])
        except Exception:
            r['points'] = []
    return routes


@router.get("/volunteers/{volunteer_id}/active_route")
def active_route(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    # return the in-progress route for volunteer if any
    route = vdb.get_active_route(volunteer_id)
    if not route:
        return {}
    try:
        route['points'] = json.loads(route.get('points') or '[]')
    except Exception:
        route['points'] = []
    return route


@router.get("/volunteers/{volunteer_id}/notifications")
def volunteer_notifications(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    v = vdb.get_volunteer_by_id(volunteer_id)
    if not v:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    notes = vdb.get_notifications(volunteer_id)
    return notes


@router.patch("/volunteers/notifications/{notification_id}/read")
def volunteer_mark_notification_read(notification_id: int, current_user: dict = Depends(get_current_user)):
    note = vdb.get_notification_by_id(notification_id)
    if not note:
        raise HTTPException(status_code=404, detail="Notification not found")
    ensure_owner_or_admin(current_user, "volunteer", note.get("volunteer_id"))
    vdb.mark_notification_read(notification_id)
    return {"ok": True}


@router.get("/volunteers/{volunteer_id}/rating")
def get_volunteer_rating(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT ROUND(AVG(dr.rating)::numeric, 1) as average, COUNT(*) as count
            FROM delivery_ratings dr
            WHERE dr.volunteer_id = %s
            """,
            (volunteer_id,),
        )
        row = cur.fetchone()
        return {
            "average": float(row["average"]) if row["average"] else None,
            "count": int(row["count"]),
        }


@router.post("/volunteers/route/{route_id}/finish")
def finish_route(route_id: int, payload: vschemas.FinishRouteRequest, current_user: dict = Depends(get_current_user)):
    route = _require_route_owner(route_id, current_user, require_active=True)

    try:
        points = json.loads(route.get('points') or '[]')
    except Exception:
        points = []

    # §8: undelivered tickets return to 'open' — but for them to be servable the
    # lot must come back to the witrine, so revert it too (audit Q12). Previously
    # finish_route reopened tickets WITHOUT reverting the lot, stranding them on a
    # still-'taken' lot until their 48h TTL. The shared helper reopens them if the
    # lot came back, or cancels them if the shop already confirmed the hand-over.
    # Revert + route close share ONE transaction: a crash between them would leave
    # the lot reverted while the route stays 'in_progress', and the next reassign
    # tick would re-revert and wrongly cancel the just-reopened tickets.
    from backend.background import revert_route_lot
    with get_db_cursor() as cur:
        revert_route_lot(cur, route.get('lot_id'), points)
        cur.execute(
            "UPDATE volunteer_routes SET status = 'finished', finished_at = %s WHERE id = %s",
            (datetime.now(timezone.utc), route_id),
        )
    return {'ok': True}


@router.post("/volunteers/route/{route_id}/attempt_delivery")
def attempt_delivery(route_id: int, payload: vschemas.CompletePointRequest, current_user: dict = Depends(get_current_user)):
    route = _require_route_owner(route_id, current_user, require_active=True)

    try:
        points = json.loads(route['points'])
    except Exception:
        points = []

    target_idx = None
    for i, p in enumerate(points):
        if p.get('kind') == 'ticket' and p.get('ticket_id') == payload.ticket_id:
            target_idx = i
            break

    if target_idx is None:
        raise HTTPException(status_code=404, detail="Point not found in route")

    if points[target_idx].get('done'):
        raise HTTPException(status_code=400, detail="Point already completed")

    attempt_count = points[target_idx].get('attempt_count', 0) + 1
    points[target_idx]['attempt_count'] = attempt_count
    released = False

    with get_db_cursor() as cur:
        cur.execute("SELECT needy_id FROM tickets WHERE id = %s", (payload.ticket_id,))
        row = cur.fetchone()
        if row:
            needy_id = row['needy_id']
            if attempt_count >= MAX_DELIVERY_ATTEMPTS:
                # §8: drop the ticket from the route, return it to open so the
                # dispatcher (or another volunteer) can pick it up.
                cur.execute(
                    "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, assigned_volunteer_id = NULL WHERE id = %s AND status = 'assigned'",
                    (payload.ticket_id,),
                )
                points[target_idx]['done'] = True
                points[target_idx]['released'] = True
                released = True
                msg = (
                    f"Волонтёр приходил {attempt_count} раза, доставка не состоялась. "
                    "Тикет возвращён в очередь — свяжитесь со службой поддержки."
                )
            else:
                msg = f"Волонтёр приходил, но вы не открыли дверь. Попытка #{attempt_count}. Ожидаем звонка в течение 10 минут."
            cur.execute(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                (needy_id, 'delivery_attempted', msg, datetime.now(timezone.utc)),
            )
            try:
                telegram_service.notify_needy(needy_id, msg)
            except Exception:
                pass

    vdb.update_route_points(route_id, json.dumps(points, ensure_ascii=False))
    return {'ok': True, 'attempt_count': attempt_count, 'released': released}


@router.get("/volunteers/{volunteer_id}/stats")
def volunteer_stats(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) as total_routes FROM volunteer_routes WHERE volunteer_id = %s",
            (volunteer_id,),
        )
        total_routes = cur.fetchone()['total_routes']

        cur.execute(
            "SELECT COUNT(*) as total_deliveries FROM tickets WHERE assigned_volunteer_id = %s AND status = 'fulfilled'",
            (volunteer_id,),
        )
        total_deliveries = cur.fetchone()['total_deliveries']

        cur.execute(
            """
            SELECT COALESCE(SUM(l.initial_quantity * l.unit_weight_kg), 0) as total_kg
            FROM volunteer_routes vr
            JOIN lots l ON l.id = vr.lot_id
            WHERE vr.volunteer_id = %s AND vr.status = 'finished'
            """,
            (volunteer_id,),
        )
        total_kg = cur.fetchone()['total_kg']

        cur.execute(
            """
            SELECT ROUND(AVG(rating)::numeric, 1) as avg_rating, COUNT(*) as rating_count
            FROM delivery_ratings
            WHERE volunteer_id = %s
            """,
            (volunteer_id,),
        )
        rating_row = cur.fetchone()

        # §28 achievements: night courier = a delivery confirmed after 20:00
        # local time; sprinter = avg route under 20 min across ≥3 routes.
        cur.execute(
            """
            SELECT COUNT(*) as cnt FROM tickets
            WHERE assigned_volunteer_id = %s AND status = 'fulfilled'
              AND fulfilled_at IS NOT NULL
              AND EXTRACT(HOUR FROM fulfilled_at AT TIME ZONE %s) >= 20
            """,
            (volunteer_id, LOCAL_TZ_NAME),
        )
        night_count = cur.fetchone()['cnt']

        cur.execute(
            """
            SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60) as avg_min, COUNT(*) as cnt
            FROM volunteer_routes
            WHERE volunteer_id = %s AND status = 'finished' AND finished_at IS NOT NULL
            """,
            (volunteer_id,),
        )
        speed_row = cur.fetchone()

    achievements = []
    if total_deliveries >= 1:
        achievements.append('first_delivery')
    if float(total_kg) >= 100:
        achievements.append('kg_100')
    if night_count > 0:
        achievements.append('night_courier')
    if speed_row['cnt'] >= 3 and speed_row['avg_min'] is not None and float(speed_row['avg_min']) < 20:
        achievements.append('sprinter')

    return {
        'total_routes': total_routes,
        'total_deliveries': total_deliveries,
        'total_kg': float(total_kg),
        'avg_rating': float(rating_row['avg_rating']) if rating_row['avg_rating'] else None,
        'rating_count': int(rating_row['rating_count']),
        'achievements': achievements,
        'level': compute_level(total_deliveries, float(total_kg)),
    }


@router.get("/volunteers/{volunteer_id}/thanks")
def volunteer_thanks(volunteer_id: int, limit: int = 30, offset: int = 0,
                     current_user: dict = Depends(get_current_user)):
    """Feed of thank-you notes: ratings this volunteer received that carry a
    written comment. Cheapest possible motivation — the data already lives in
    `delivery_ratings.comment`, the volunteer just never saw it."""
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    limit = clamp(limit, 1, 100)
    offset = max(0, offset)
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT dr.rating, dr.comment, dr.created_at, l.category
            FROM delivery_ratings dr
            JOIN tickets t ON t.id = dr.ticket_id
            LEFT JOIN lots l ON l.id = t.lot_id
            WHERE dr.volunteer_id = %s
              AND dr.comment IS NOT NULL AND TRIM(dr.comment) <> ''
            ORDER BY dr.created_at DESC
            LIMIT %s OFFSET %s
            """,
            (volunteer_id, limit, offset),
        )
        rows = cur.fetchall()
    return [
        {
            'rating': r['rating'],
            'comment': r['comment'],
            'category': r['category'],
            'created_at': r['created_at'].isoformat() if r['created_at'] else None,
        }
        for r in rows
    ]


# ── Corporate volunteering: teams ────────────────────────────────────────────

def _team_summary(team_id: int):
    with get_db_cursor() as cur:
        cur.execute("SELECT id, name, join_code, created_at FROM teams WHERE id = %s", (team_id,))
        team = cur.fetchone()
        if not team:
            return None
        cur.execute("SELECT COUNT(*) AS n FROM volunteers WHERE team_id = %s", (team_id,))
        members = cur.fetchone()["n"]
        cur.execute(
            """
            SELECT COUNT(t.id) AS deliveries
            FROM tickets t JOIN volunteers v ON v.id = t.assigned_volunteer_id
            WHERE v.team_id = %s AND t.status = 'fulfilled'
            """,
            (team_id,),
        )
        deliveries = cur.fetchone()["deliveries"]
        cur.execute(
            """
            SELECT COALESCE(SUM(l.initial_quantity * l.unit_weight_kg), 0) AS kg
            FROM volunteer_routes vr
            JOIN lots l ON l.id = vr.lot_id
            JOIN volunteers v ON v.id = vr.volunteer_id
            WHERE v.team_id = %s AND vr.status = 'finished'
            """,
            (team_id,),
        )
        kg = float(cur.fetchone()["kg"])
    return {**dict(team), "members": members, "deliveries": deliveries, "kg": kg}


@router.get("/volunteers/{volunteer_id}/team")
def get_team(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    vol = vdb.get_volunteer_by_id(volunteer_id)
    if not vol:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    if not vol.get("team_id"):
        return {"team": None}
    return {"team": _team_summary(vol["team_id"])}


@router.post("/volunteers/{volunteer_id}/team/create")
def create_team(volunteer_id: int, payload: vschemas.TeamCreate, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    vol = vdb.get_volunteer_by_id(volunteer_id)
    if not vol:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    if vol.get("team_id"):
        raise HTTPException(status_code=400, detail="Вы уже состоите в команде — сначала покиньте её")
    name = (payload.name or "").strip()
    if len(name) < 3:
        raise HTTPException(status_code=400, detail="Название команды — минимум 3 символа")
    from backend.utils import generate_join_code
    # Retry on the (astronomically unlikely) join-code collision.
    for _ in range(5):
        try:
            with get_db_cursor() as cur:
                cur.execute(
                    "INSERT INTO teams (name, join_code) VALUES (%s, %s) RETURNING id",
                    (name[:80], generate_join_code()),
                )
                team_id = cur.fetchone()["id"]
                cur.execute("UPDATE volunteers SET team_id = %s WHERE id = %s", (team_id, volunteer_id))
            break
        except psycopg2.errors.UniqueViolation:
            continue
    else:
        raise HTTPException(status_code=500, detail="Не удалось создать команду, попробуйте ещё раз")
    return {"team": _team_summary(team_id)}


@router.post("/volunteers/{volunteer_id}/team/join")
def join_team(volunteer_id: int, payload: vschemas.TeamJoin, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    vol = vdb.get_volunteer_by_id(volunteer_id)
    if not vol:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    if vol.get("team_id"):
        raise HTTPException(status_code=400, detail="Вы уже состоите в команде — сначала покиньте её")
    code = (payload.code or "").strip().upper()
    with get_db_cursor() as cur:
        cur.execute("SELECT id FROM teams WHERE join_code = %s", (code,))
        team = cur.fetchone()
        if not team:
            raise HTTPException(status_code=404, detail="Команда с таким кодом не найдена")
        cur.execute("UPDATE volunteers SET team_id = %s WHERE id = %s", (team["id"], volunteer_id))
    return {"team": _team_summary(team["id"])}


@router.post("/volunteers/{volunteer_id}/team/leave")
def leave_team(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    with get_db_cursor() as cur:
        cur.execute("UPDATE volunteers SET team_id = NULL WHERE id = %s", (volunteer_id,))
        # Empty teams are garbage-collected so abandoned codes don't pile up.
        cur.execute(
            "DELETE FROM teams t WHERE NOT EXISTS (SELECT 1 FROM volunteers v WHERE v.team_id = t.id)"
        )
    return {"ok": True}


@router.patch("/volunteers/{volunteer_id}/location")
def update_location(volunteer_id: int, payload: vschemas.LocationUpdate, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "volunteer", volunteer_id)
    vdb.update_volunteer_location(volunteer_id, payload.lat, payload.lon)
    # Write-through cache: recipients poll this every 15s during delivery —
    # serve the hot value from Redis, Postgres stays the source of truth.
    # CONTRACT: this cache is keyed vol:loc:{id} and read back by get_location
    # below. The Go geows service (go-services/geows) can also serve this
    # endpoint but writes Postgres only — it has no Redis client. The reverse
    # proxy must route this PATCH and the GET to the SAME service, or reads
    # here will be up to TTL_LOCATION seconds stale after a geows write.
    from backend import cache
    cache.set_json(
        f"vol:loc:{volunteer_id}",
        {"lat": payload.lat, "lon": payload.lon, "updated_at": datetime.now(timezone.utc)},
        cache.TTL_LOCATION,
    )
    return {'ok': True}


@router.get("/volunteers/{volunteer_id}/location")
def get_location(volunteer_id: int, current_user: dict = Depends(get_current_user)):
    # The volunteer themselves, an admin, or a recipient whose ticket is assigned
    # to this volunteer may read the live location (needed for delivery tracking).
    role = current_user.get("role")
    allowed = (
        role == "admin"
        or (role == "volunteer" and current_user.get("related_id") == volunteer_id)
        or (role == "needy" and vdb.needy_has_volunteer(current_user.get("related_id"), volunteer_id))
    )
    if not allowed:
        raise HTTPException(status_code=403, detail="Forbidden")
    from backend import cache
    cached = cache.get_json(f"vol:loc:{volunteer_id}")
    if cached is not None:
        return cached
    loc = vdb.get_volunteer_location(volunteer_id)
    if not loc:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    return loc
