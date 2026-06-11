from datetime import timedelta

from backend.auth import (
    create_access_token,
    decode_access_token,
    get_password_hash,
    verify_password,
)


def test_password_roundtrip():
    hashed = get_password_hash("s3cret-password")
    assert verify_password("s3cret-password", hashed)
    assert not verify_password("wrong", hashed)


def test_malformed_hash_reads_as_wrong_password():
    # A corrupted/empty hash in the DB must mean 401, not a 500 in passlib.
    assert verify_password("anything", "not-a-bcrypt-hash") is False
    assert verify_password("anything", "") is False


def test_token_roundtrip():
    token = create_access_token({"sub": "alice", "role": "volunteer", "related_id": 7})
    payload = decode_access_token(token)
    assert payload["sub"] == "alice"
    assert payload["role"] == "volunteer"
    assert payload["related_id"] == 7


def test_expired_token_rejected():
    token = create_access_token({"sub": "bob"}, expires_delta=timedelta(minutes=-1))
    assert decode_access_token(token) is None


def test_garbage_token_rejected():
    assert decode_access_token("not.a.jwt") is None
