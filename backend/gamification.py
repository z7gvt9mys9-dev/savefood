"""Volunteer levels (§28 gamification, extension).

A level is computed on the fly from the same aggregates the stats endpoint
already collects — no extra tables. Points blend deliveries and rescued kg
so both the «many small trips» and the «few heavy hauls» play styles level up.
"""
from typing import Any, Dict

POINTS_PER_DELIVERY = 10.0
POINTS_PER_KG = 1.0

# (code, points threshold) — ordered ascending. i18n labels live on the frontend.
LEVELS = [
    ("novice", 0),        # Новичок
    ("helper", 50),       # Помощник
    ("courier", 200),     # Курьер добра
    ("guardian", 600),    # Хранитель района
    ("city_hero", 1500),  # Герой города
]


def compute_points(deliveries: int, kg: float) -> float:
    return deliveries * POINTS_PER_DELIVERY + kg * POINTS_PER_KG


def compute_level(deliveries: int, kg: float) -> Dict[str, Any]:
    """Current level + progress towards the next one (for the progress bar)."""
    points = compute_points(deliveries or 0, float(kg or 0))
    current = LEVELS[0]
    nxt = None
    for i, (code, threshold) in enumerate(LEVELS):
        if points >= threshold:
            current = (code, threshold)
            nxt = LEVELS[i + 1] if i + 1 < len(LEVELS) else None
    if nxt is None:
        progress = 1.0
        to_next = 0.0
    else:
        span = nxt[1] - current[1]
        progress = (points - current[1]) / span if span > 0 else 1.0
        to_next = max(0.0, nxt[1] - points)
    return {
        "code": current[0],
        "points": round(points, 1),
        "next_code": nxt[0] if nxt else None,
        "points_to_next": round(to_next, 1),
        "progress": round(min(1.0, max(0.0, progress)), 3),
    }
