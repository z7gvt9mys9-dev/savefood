"""Partner API key generation/hashing and webhook signing — pure logic."""
import hashlib
import hmac

from backend.partner_api import KEY_PREFIX, generate_api_key, hash_key
from backend.webhook_service import event_matches, sign


def test_generated_key_shape():
    secret, prefix, key_hash = generate_api_key()
    assert secret.startswith(KEY_PREFIX)
    assert len(secret) == len(KEY_PREFIX) + 48  # 24 bytes hex
    assert secret.startswith(prefix)
    assert key_hash == hashlib.sha256(secret.encode()).hexdigest()


def test_keys_are_unique():
    secrets_seen = {generate_api_key()[0] for _ in range(50)}
    assert len(secrets_seen) == 50


def test_prefix_does_not_leak_secret():
    secret, prefix, _ = generate_api_key()
    # prefix shows the marker + 6 chars of entropy — not enough to brute-force,
    # but the rest of the secret must not be derivable from it.
    assert len(prefix) < len(secret) / 2


def test_hash_key_deterministic():
    assert hash_key("abc") == hash_key("abc")
    assert hash_key("abc") != hash_key("abd")
    assert hash_key("") == hashlib.sha256(b"").hexdigest()


def test_webhook_signature_known_vector():
    body = b'{"event":"lot.taken","data":{"lot_id":1}}'
    secret = "whsec_test"
    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    assert sign(secret, body) == expected


def test_webhook_signature_changes_with_body_and_secret():
    assert sign("s", b"a") != sign("s", b"b")
    assert sign("s1", b"a") != sign("s2", b"a")


def test_event_matching():
    assert event_matches("*", "lot.taken")
    assert event_matches("lot.taken,receipt.parsed", "lot.taken")
    assert not event_matches("lot.taken", "lot.confirmed")
    assert event_matches("", "lot.taken")  # empty defaults to '*'
    assert event_matches(" lot.confirmed , * ", "anything")
