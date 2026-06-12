"""Wave: GPS anti-fraud hardening + recipient address privacy.

Covers the pure/monkeypatchable parts:
- coarsen_coord: open requests on the volunteer map must not reveal the house;
- _verify_position_against_pings: client-supplied confirmation coordinates are
  cross-checked against the latest geolocation ping (§13/§27).
"""
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException

import backend.volunteer.routes as vol_routes
from backend.utils import coarsen_coord, COARSE_GRID_DEG


# ── coarsen_coord ─────────────────────────────────────────────────────────────

def test_coarsen_snaps_to_grid():
    v = coarsen_coord(43.238949)
    assert abs(v / COARSE_GRID_DEG - round(v / COARSE_GRID_DEG)) < 1e-6


def test_coarsen_moves_point_less_than_grid():
    lat = 43.238949
    assert abs(coarsen_coord(lat) - lat) <= COARSE_GRID_DEG / 2 + 1e-9


def test_coarsen_hides_building_scale_differences():
    # Two addresses ~100 m apart must collapse into the same grid cell more
    # often than not; exact equality for these two known points.
    assert coarsen_coord(43.2381) == coarsen_coord(43.2389)


def test_coarsen_is_deterministic():
    # No jitter: repeated map loads must not let an observer average out noise.
    assert coarsen_coord(43.238949) == coarsen_coord(43.238949)


# ── _verify_position_against_pings ────────────────────────────────────────────

def _patch_location(monkeypatch, loc):
    monkeypatch.setattr(vol_routes.vdb, "get_volunteer_location", lambda vid: loc)


def test_fresh_ping_far_away_rejected(monkeypatch):
    _patch_location(monkeypatch, {
        "lat": 43.25, "lon": 76.95,
        "updated_at": datetime.now(timezone.utc) - timedelta(minutes=1),
    })
    with pytest.raises(HTTPException) as exc:
        # ~0.1° of latitude ≈ 11 km away from the claimed position
        vol_routes._verify_position_against_pings(1, 43.35, 76.95)
    assert exc.value.status_code == 400


def test_fresh_ping_nearby_passes(monkeypatch):
    _patch_location(monkeypatch, {
        "lat": 43.25, "lon": 76.95,
        "updated_at": datetime.now(timezone.utc) - timedelta(minutes=1),
    })
    vol_routes._verify_position_against_pings(1, 43.251, 76.951)  # ~140 m


def test_stale_ping_not_compared(monkeypatch):
    # A tracker that went quiet an hour ago must not block an honest delivery.
    _patch_location(monkeypatch, {
        "lat": 43.25, "lon": 76.95,
        "updated_at": datetime.now(timezone.utc) - timedelta(hours=1),
    })
    vol_routes._verify_position_against_pings(1, 44.0, 77.0)


def test_no_ping_history_passes(monkeypatch):
    _patch_location(monkeypatch, None)
    vol_routes._verify_position_against_pings(1, 43.25, 76.95)
    _patch_location(monkeypatch, {"lat": None, "lon": None, "updated_at": None})
    vol_routes._verify_position_against_pings(1, 43.25, 76.95)
