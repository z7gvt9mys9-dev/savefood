from backend.needs_match import matches_preferences


def test_positive_mention_matches():
    assert matches_preferences("Молочные продукты", "У меня 3 детей, нужна молочка и каши")
    assert matches_preferences("Выпечка", "хлеб нужен всегда")
    assert matches_preferences("Готовая еда", "нужны каши и крупа")


def test_restriction_beats_mention():
    assert not matches_preferences("Молочные продукты", "аллергия на молоко")
    assert not matches_preferences("Выпечка", "не ем хлеб")
    assert not matches_preferences("Молочные продукты", "без молочных продуктов, пожалуйста")


def test_restriction_in_other_clause_does_not_block():
    # «люблю молоко» and «нет аллергий» are separate clauses — the restriction
    # word in the second clause must not poison the dairy match in the first.
    assert matches_preferences("Молочные продукты", "люблю молоко, нет аллергий на орехи")


def test_unrelated_preferences_do_not_match():
    assert not matches_preferences("Выпечка", "нужны овощи и фрукты")


def test_empty_inputs():
    assert not matches_preferences(None, "хлеб")
    assert not matches_preferences("Выпечка", None)
    assert not matches_preferences("Выпечка", "")
    assert not matches_preferences("Неизвестная категория", "хлеб")
