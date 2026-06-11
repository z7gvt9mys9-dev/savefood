from backend.gamification import LEVELS, compute_level, compute_points


def test_zero_is_novice():
    lvl = compute_level(0, 0)
    assert lvl["code"] == "novice"
    assert lvl["points"] == 0
    assert lvl["next_code"] == "helper"
    assert lvl["progress"] == 0


def test_points_blend_deliveries_and_kg():
    assert compute_points(3, 25) == 3 * 10 + 25


def test_threshold_boundary_promotes():
    # exactly 50 points (5 deliveries) → helper, not novice
    lvl = compute_level(5, 0)
    assert lvl["code"] == "helper"


def test_top_level_has_no_next():
    lvl = compute_level(1000, 1000)
    assert lvl["code"] == "city_hero"
    assert lvl["next_code"] is None
    assert lvl["progress"] == 1.0
    assert lvl["points_to_next"] == 0


def test_progress_is_within_unit_interval():
    for deliveries in (0, 1, 7, 19, 60, 149, 500):
        lvl = compute_level(deliveries, deliveries * 2.5)
        assert 0.0 <= lvl["progress"] <= 1.0


def test_levels_are_sorted_ascending():
    thresholds = [t for _, t in LEVELS]
    assert thresholds == sorted(thresholds)
