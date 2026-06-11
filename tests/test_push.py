from backend.push_service import strip_html


def test_strip_html_removes_telegram_markup():
    assert strip_html("Волонтёр <b>Алексей</b> взял ваш лот") == "Волонтёр Алексей взял ваш лот"
    assert strip_html("🛒 <i>тест</i>") == "🛒 тест"


def test_strip_html_plain_text_untouched():
    assert strip_html("обычный текст") == "обычный текст"
    assert strip_html("") == ""
    assert strip_html(None) == ""
