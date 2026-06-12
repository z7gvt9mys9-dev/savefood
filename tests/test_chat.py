from backend.chat import participant_role

CTX = {"id": 7, "needy_id": 42, "assigned_volunteer_id": 9, "status": "assigned"}


def test_owner_needy_is_participant():
    assert participant_role({"role": "needy", "related_id": 42}, CTX) == "needy"


def test_assigned_volunteer_is_participant():
    assert participant_role({"role": "volunteer", "related_id": 9}, CTX) == "volunteer"


def test_admin_observes():
    assert participant_role({"role": "admin", "related_id": None}, CTX) == "admin"


def test_other_needy_rejected():
    assert participant_role({"role": "needy", "related_id": 999}, CTX) is None


def test_other_volunteer_rejected():
    assert participant_role({"role": "volunteer", "related_id": 1}, CTX) is None


def test_unassigned_ticket_has_no_volunteer_participant():
    ctx = {**CTX, "assigned_volunteer_id": None}
    assert participant_role({"role": "volunteer", "related_id": 9}, ctx) is None
