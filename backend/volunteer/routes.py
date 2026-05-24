from fastapi import APIRouter, HTTPException
import json
import math

from backend.volunteer import db as vdb, schemas as vschemas
from backend.shop import db as shopdb
from backend.needy import db as needydb
from datetime import datetime, timezone
from datetime import time as dtime

router = APIRouter()


@router.post("/volunteers/register")
def register(vol: vschemas.VolunteerCreate):
    vid = vdb.create_volunteer(vol.name, vol.contact, vol.lat, vol.lon)
    return {"id": vid}


@router.get("/volunteers/map")
def get_map_points():
    # return shops with their active lots (grouped by shop) and needy tickets with coords
    shops_map = {}
    conn = shopdb.get_conn()
    cur = conn.cursor()
    cur.execute("SELECT s.id as shop_id, s.name, s.lat, s.lon, l.id as lot_id, l.description, l.quantity FROM shops s JOIN lots l ON s.id = l.shop_id WHERE l.status = 'active'")
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
                'lots': []
            }
        shops_map[sid]['lots'].append({
            'lot_id': r['lot_id'],
            'description': r['description'],
            'quantity': r['quantity']
        })
    conn.close()

    shops = list(shops_map.values())

    tickets = []
    conn = needydb.get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM tickets WHERE status = 'open' AND lat IS NOT NULL AND lon IS NOT NULL")
    for r in cur.fetchall():
        tickets.append({
            'ticket_id': r['id'],
            'needy_id': r['needy_id'],
            'items': r['items'],
            'available_time': r['available_time'],
            'lat': r['lat'],
            'lon': r['lon']
        })
    conn.close()

    return {'shops': shops, 'tickets': tickets}

@router.get("/volunteers/{volunteer_id}", response_model=vschemas.VolunteerOut)
def get_volunteer(volunteer_id: int):
    v = vdb.get_volunteer_by_id(volunteer_id)
    if not v:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    return v


def haversine(a, b):
    # a and b are (lat, lon)
    R = 6371
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = math.sin(dlat/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(dlon/2)**2
    return 2*R*math.asin(math.sqrt(h))

def is_available_now(available_time: str) -> bool:
    if not available_time:
        return True
    # expected format: "HH:MM-HH:MM"
    try:
        parts = available_time.split('-')
        if len(parts) != 2:
            return True
        start = parts[0].strip()
        end = parts[1].strip()
        sh, sm = [int(x) for x in start.split(':')]
        eh, em = [int(x) for x in end.split(':')]
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


@router.post("/volunteers/{volunteer_id}/start_route")
def start_route(volunteer_id: int, payload: vschemas.StartRouteRequest):
    vol = vdb.get_volunteer_by_id(volunteer_id)
    if not vol:
        raise HTTPException(status_code=404, detail="Volunteer not found")

    # get lot and its shop coordinates
    conn = shopdb.get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM lots WHERE id = ?", (payload.lot_id,))
    lot = cur.fetchone()
    if not lot:
        conn.close()
        raise HTTPException(status_code=404, detail="Lot not found")
    cur.execute("SELECT * FROM shops WHERE id = ?", (lot['shop_id'],))
    shop = cur.fetchone()
    conn.close()
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")

    if shop['lat'] is None or shop['lon'] is None:
        raise HTTPException(status_code=400, detail="Shop has no coordinates")
    
    # try to take the lot so other volunteers cannot take it
    taken = shopdb.take_lot(payload.lot_id, vol.get('name') or f"volunteer_{volunteer_id}")
    if not taken:
        raise HTTPException(status_code=400, detail="Lot is not available")

    # collect open tickets with coords
    conn = needydb.get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM tickets WHERE status = 'open' AND lat IS NOT NULL AND lon IS NOT NULL")
    tickets = [dict(r) for r in cur.fetchall()]
    # filter by available_time (only include tickets where needy is at home now or not specified)
    tickets = [t for t in tickets if is_available_now(t.get('available_time'))]
    conn.close()



    # greedy nearest neighbor from shop
    order = []
    remaining = tickets.copy()
    current = (shop['lat'], shop['lon'])
    max_stops = payload.max_stops or 10
    while remaining and len(order) < max_stops:
        # find nearest
        nearest = min(remaining, key=lambda t: haversine(current, (t['lat'], t['lon'])))
        order.append(nearest)
        remaining.remove(nearest)
        current = (nearest['lat'], nearest['lon'])

    # build points: first shop, then tickets
    points = []
    points.append({'kind':'shop','lat':shop['lat'],'lon':shop['lon'],'description':shop['name'],'ticket_id':None})
    for t in order:
        points.append({'kind':'ticket','lat':t['lat'],'lon':t['lon'],'description':t['items'],'ticket_id':t['id']})

    points_json = json.dumps(points, ensure_ascii=False)
    route_id = vdb.create_route(volunteer_id, points_json, payload.lot_id)

    # persist notification for volunteer that route was created
    try:
        vdb.create_notification(volunteer_id, 'route_created', f'Route {route_id} created')
    except Exception:
        pass

    return {'route_id': route_id, 'points': points}


@router.post("/volunteers/route/{route_id}/complete_point")
def complete_point(route_id: int, payload: vschemas.CompletePointRequest):
    # verify route and volunteer
    route = vdb.get_route_by_id(route_id)
    if not route:
        raise HTTPException(status_code=404, detail="Route not found")
    if route['volunteer_id'] != payload.volunteer_id:
        raise HTTPException(status_code=403, detail="Volunteer does not own this route")

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

    point = points[target_idx]

    # if ticket point — mark ticket fulfilled
    if point.get('kind') == 'ticket' and point.get('ticket_id'):
        conn = needydb.get_conn()
        cur = conn.cursor()
        cur.execute("SELECT * FROM tickets WHERE id = ?", (point['ticket_id'],))
        t = cur.fetchone()
        if not t:
            conn.close()
            raise HTTPException(status_code=404, detail="Ticket not found")
        cur.execute("UPDATE tickets SET status = 'fulfilled', fulfilled_at = ? WHERE id = ?", (datetime.now(timezone.utc), point['ticket_id']))
        conn.commit()
        conn.close()

    # if completing shop point — notify needy that volunteer is en route
    if point.get('kind') == 'shop':
        # notify all remaining ticket points in route that are not done
        vol = vdb.get_volunteer_by_id(payload.volunteer_id)
        vol_name = vol.get('name') if vol else f"volunteer_{payload.volunteer_id}"
        conn = needydb.get_conn()
        cur = conn.cursor()
        for p in points:
            if p.get('kind') == 'ticket' and not p.get('done') and p.get('ticket_id'):
                # fetch ticket to get needy_id and current status
                cur.execute("SELECT * FROM tickets WHERE id = ?", (p.get('ticket_id'),))
                t = cur.fetchone()
                if not t:
                    continue
                # only notify if ticket is still open
                if t['status'] != 'open':
                    continue
                needy_id = t['needy_id']
                payload_text = f"Volunteer {vol_name} is en route to your request (ticket {p.get('ticket_id')})"
                cur.execute(
                    "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
                    (needy_id, 'volunteer_en_route', payload_text, datetime.now(timezone.utc)),
                )
        conn.commit()
        conn.close()

    # mark point as done and persist
    points[target_idx]['done'] = True
    vdb.update_route_points(route_id, json.dumps(points, ensure_ascii=False))

    return {'ok': True}


@router.get("/volunteers/{volunteer_id}/history")
def history(volunteer_id: int):
    routes = vdb.get_routes_by_volunteer(volunteer_id)
    # parse points JSON
    for r in routes:
        try:
            r['points'] = json.loads(r['points'])
        except Exception:
            r['points'] = []
    return routes


@router.get("/volunteers/{volunteer_id}/active_route")
def active_route(volunteer_id: int):
    # return the in-progress route for volunteer if any
    conn = vdb.get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM volunteer_routes WHERE volunteer_id = ? AND status = 'in_progress' ORDER BY started_at DESC", (volunteer_id,))
    row = cur.fetchone()
    conn.close()
    if not row:
        return {}
    try:
        r = dict(row)
        r['points'] = json.loads(r.get('points') or '[]')
    except Exception:
        r['points'] = []
    return r


@router.get("/volunteers/{volunteer_id}/notifications")
def volunteer_notifications(volunteer_id: int):
    v = vdb.get_volunteer_by_id(volunteer_id)
    if not v:
        raise HTTPException(status_code=404, detail="Volunteer not found")
    notes = vdb.get_notifications(volunteer_id)
    return notes


@router.patch("/volunteers/notifications/{notification_id}/read")
def volunteer_mark_notification_read(notification_id: int):
    vdb.mark_notification_read(notification_id)
    return {"ok": True}


@router.post("/volunteers/route/{route_id}/finish")
def finish_route(route_id: int, payload: vschemas.FinishRouteRequest):
    # verify route exists and volunteer owns it
    route = vdb.get_route_by_id(route_id)
    if not route:
        raise HTTPException(status_code=404, detail="Route not found")
    if route['volunteer_id'] != payload.volunteer_id:
        raise HTTPException(status_code=403, detail="Volunteer does not own this route")

    vdb.finish_route(route_id)
    return {'ok': True}