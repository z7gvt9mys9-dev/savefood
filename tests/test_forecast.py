from backend.forecast import MIN_AVG_KG, build_forecast


def test_average_over_fixed_weeks():
    rows = [{"isodow": 5, "category": "Выпечка", "kg": 96}]
    fc = build_forecast(rows, today_isodow=5, basis_weeks=8)
    assert fc["today"]["items"] == [{"category": "Выпечка", "avg_kg": 12.0}]
    assert fc["tomorrow"]["items"] == []


def test_noise_below_threshold_dropped():
    rows = [{"isodow": 3, "category": "Молочные продукты", "kg": MIN_AVG_KG * 8 - 1}]
    fc = build_forecast(rows, today_isodow=3, basis_weeks=8)
    assert fc["today"]["items"] == []


def test_items_sorted_by_volume_desc():
    rows = [
        {"isodow": 1, "category": "Выпечка", "kg": 16},
        {"isodow": 1, "category": "Овощи/Фрукты", "kg": 80},
    ]
    fc = build_forecast(rows, today_isodow=1, basis_weeks=8)
    assert [i["category"] for i in fc["today"]["items"]] == ["Овощи/Фрукты", "Выпечка"]


def test_sunday_wraps_to_monday():
    fc = build_forecast([], today_isodow=7, basis_weeks=8)
    assert fc["tomorrow"]["isodow"] == 1
    assert fc["today"]["day_name"] == "воскресенье"
    assert fc["tomorrow"]["day_name"] == "понедельник"


def test_uncategorized_lots_still_forecast():
    rows = [{"isodow": 2, "category": None, "kg": 24}]
    fc = build_forecast(rows, today_isodow=2, basis_weeks=8)
    assert fc["today"]["items"] == [{"category": "Без категории", "avg_kg": 3.0}]
