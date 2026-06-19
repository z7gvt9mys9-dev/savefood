"""Route construction logic (§14): NN + 2-opt ordering and time-window filter."""
from datetime import datetime

import backend.volunteer.routes as vol_routes
from backend.volunteer.routes import _optimize_stop_order, _route_length_km, haversine


def _t(tid, lat, lon):
    return {"id": tid, "lat": lat, "lon": lon}


def test_haversine_symmetry():
    a, b = (43.25, 76.95), (43.30, 76.90)
    assert abs(haversine(a, b) - haversine(b, a)) < 1e-9


def test_optimize_keeps_all_stops():
    start = (0.0, 0.0)
    tickets = [_t(1, 0.0, 0.3), _t(2, 0.0, 0.1), _t(3, 0.0, 0.2)]
    order = _optimize_stop_order(tickets, start)
    assert sorted(t["id"] for t in order) == [1, 2, 3]


def test_optimize_orders_collinear_points_by_distance():
    # Points on a line east of the shop: any non-monotonic order is a zig-zag.
    start = (0.0, 0.0)
    tickets = [_t(3, 0.0, 0.3), _t(1, 0.0, 0.1), _t(2, 0.0, 0.2)]
    order = _optimize_stop_order(tickets, start)
    assert [t["id"] for t in order] == [1, 2, 3]


def test_two_opt_no_worse_than_any_naive_order():
    start = (0.0, 0.0)
    tickets = [_t(1, 0.05, 0.2), _t(2, -0.05, 0.1), _t(3, 0.0, 0.3), _t(4, 0.1, 0.05)]
    optimized = _route_length_km(_optimize_stop_order(tickets, start), start)
    as_given = _route_length_km(tickets, start)
    assert optimized <= as_given + 1e-9


def test_single_ticket_passthrough():
    tickets = [_t(1, 0.0, 0.1)]
    assert _optimize_stop_order(tickets, (0, 0)) == tickets


class _NoonDT(datetime):
    """datetime.now() frozen at 12:00 local regardless of tz argument."""
    @classmethod
    def now(cls, tz=None):
        return cls(2026, 6, 11, 12, 0, tzinfo=tz)


def test_is_available_now_window(monkeypatch):
    monkeypatch.setattr(vol_routes, "datetime", _NoonDT)
    assert vol_routes.is_available_now("10:00-14:00") is True
    assert vol_routes.is_available_now("13:00-14:00") is False
    # overnight window 22:00-02:00 does not include noon
    assert vol_routes.is_available_now("22:00-02:00") is False
    # overnight window 09:00-01:00 includes noon
    assert vol_routes.is_available_now("09:00-01:00") is True


def test_is_available_now_lenient_on_garbage():
    assert vol_routes.is_available_now("") is True
    assert vol_routes.is_available_now(None) is True
    assert vol_routes.is_available_now("not-a-window") is True
    assert vol_routes.is_available_now("10:00") is True


def test_window_open_within_horizon(monkeypatch):
    monkeypatch.setattr(vol_routes, "datetime", _NoonDT)  # now = 12:00 local
    H = 120
    # open right now → True regardless of horizon
    assert vol_routes.window_open_within("10:00-14:00", H) is True
    # opens in 60 min (13:00) → within the 120-min horizon → True (§59/Q1-A:
    # the working person whose window opens soon is no longer dropped+cancelled)
    assert vol_routes.window_open_within("13:00-14:00", H) is True
    # opens in 180 min (15:00) → beyond the horizon → False
    assert vol_routes.window_open_within("15:00-16:00", H) is False
    # already closed today, reopens tomorrow → False
    assert vol_routes.window_open_within("08:00-09:00", H) is False
    # empty / unparseable → always available
    assert vol_routes.window_open_within("", H) is True
    assert vol_routes.window_open_within(None, H) is True
    # horizon 0 collapses to is_available_now (not open now → False)
    assert vol_routes.window_open_within("13:00-14:00", 0) is False
