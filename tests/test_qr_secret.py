"""Per-ticket QR secret: payload format + uniqueness (pure helpers, no DB)."""
from backend.utils import build_qr_code, generate_qr_secret


def test_qr_code_includes_secret():
    assert build_qr_code(42, "AbC1_2-x") == "SF-42-AbC1_2-x"


def test_qr_code_falls_back_without_secret():
    # Legacy pre-migration tickets have no secret — keep the bare form working.
    assert build_qr_code(42, None) == "SF-42"
    assert build_qr_code(42, "") == "SF-42"


def test_secret_is_unguessable_and_unique():
    secrets_seen = {generate_qr_secret() for _ in range(200)}
    assert len(secrets_seen) == 200          # no collisions
    assert all(len(s) >= 8 for s in secrets_seen)
    # url-safe alphabet only, so it survives the SF-{id}-{secret} regex
    import re
    assert all(re.fullmatch(r"[A-Za-z0-9_-]+", s) for s in secrets_seen)
