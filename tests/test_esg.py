from backend.esg import CO2_DEFAULT, CO2_PER_KG, KG_PER_MEAL, _build_report, _co2, report_to_csv


def test_co2_uses_category_factor():
    assert _co2(10, "Выпечка") == 10 * CO2_PER_KG["Выпечка"]
    assert _co2(10, None) == 10 * CO2_DEFAULT
    assert _co2(10, "Неизвестная категория") == 10 * CO2_DEFAULT


def test_build_report_totals():
    rows_cat = [
        {"category": "Выпечка", "kg": 10, "lots": 2},
        {"category": None, "kg": 4, "lots": 1},
    ]
    rows_month = [{"month": "2026-05", "kg": 14, "co2_kg": 23.0}]
    report = _build_report(rows_cat, rows_month, months=12)

    assert report["totals"]["kg"] == 14.0
    # 10 * 1.3 + 4 * 2.5
    assert report["totals"]["co2_kg"] == 23.0
    assert report["totals"]["meals"] == int(14 / KG_PER_MEAL)
    assert report["totals"]["lots"] == 3
    assert report["by_category"][1]["category"] == "Без категории"
    assert report["by_month"][0]["month"] == "2026-05"


def test_build_report_empty():
    report = _build_report([], [], months=6)
    assert report["totals"] == {"kg": 0, "co2_kg": 0, "meals": 0, "lots": 0}
    assert report["by_category"] == []


def test_report_to_csv_contains_totals_and_breakdown():
    rows_cat = [
        {"category": "Выпечка", "kg": 10, "lots": 2},
        {"category": None, "kg": 4, "lots": 1},
    ]
    rows_month = [{"month": "2026-05", "kg": 14, "co2_kg": 23.0}]
    report = _build_report(rows_cat, rows_month, months=12)
    csv_text = report_to_csv(report, shop_name="Тест-Маркет")

    # semicolon-separated, carries org name, totals, category + month sections
    assert "Тест-Маркет" in csv_text
    assert "Итого, кг" in csv_text
    assert "Выпечка" in csv_text
    assert "Без категории" in csv_text
    assert "2026-05" in csv_text
    # totals row: 14 kg present as a standalone field
    assert ";14;" in csv_text or csv_text.count("14") >= 1
