from backend.impact import _badge_svg, _fmt_kg


def test_fmt_kg_small_and_tonnes():
    assert _fmt_kg(0) == "0 кг"
    assert _fmt_kg(12.4) == "12 кг"
    assert _fmt_kg(999) == "999 кг"
    # >= 1000 kg switches to tonnes
    assert _fmt_kg(1000) == "1 т"
    assert _fmt_kg(2500) == "2.5 т"


def test_badge_svg_is_valid_svg_with_numbers():
    svg = _badge_svg("Наш магазин", 1234, 2938, 3210.0)
    assert svg.startswith("<svg")
    assert svg.rstrip().endswith("</svg>")
    assert "Наш магазин" in svg
    assert "1.2 т" in svg  # 1234 kg rendered as tonnes
    # meals formatted with a thin space thousands separator
    assert "2 938" in svg


def test_badge_svg_escapes_shop_name():
    svg = _badge_svg("A&B <Shop>", 10, 20, 30)
    assert "<Shop>" not in svg
    assert "&amp;" in svg
    assert "&lt;Shop&gt;" in svg
