"""Partner API key generation/hashing and webhook signing — pure logic."""
import hashlib
import hmac

import backend.webhook_service as webhook_service
from backend.partner_api import KEY_PREFIX, generate_api_key, hash_key
from backend.webhook_service import _deliver, _is_safe_webhook_url, event_matches, sign


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


# --- SSRF guard for outgoing webhook delivery (no network needed) ---------


def test_ssrf_blocks_cloud_metadata():
    # AWS/GCP/Azure metadata endpoint — the classic SSRF target.
    assert _is_safe_webhook_url("http://169.254.169.254/latest/meta-data/") is False


def test_ssrf_blocks_loopback():
    assert _is_safe_webhook_url("http://127.0.0.1/") is False
    assert _is_safe_webhook_url("http://localhost/hook") is False


def test_ssrf_blocks_private_range():
    assert _is_safe_webhook_url("http://10.0.0.5/x") is False
    assert _is_safe_webhook_url("http://192.168.1.1/x") is False


def test_ssrf_blocks_non_http_scheme():
    assert _is_safe_webhook_url("file:///etc/passwd") is False
    assert _is_safe_webhook_url("ftp://example.com/x") is False
    assert _is_safe_webhook_url("gopher://127.0.0.1/") is False


def test_ssrf_allows_public_literal():
    # Literal public IP avoids real DNS while still exercising the IP checks.
    assert _is_safe_webhook_url("https://93.184.216.34/hook") is True


def test_ssrf_allows_public_host(monkeypatch):
    # Public DNS name, resolver monkeypatched to a public IP (no real network).
    def fake_getaddrinfo(host, port, *args, **kwargs):
        return [(2, 1, 6, "", ("93.184.216.34", port or 0))]

    monkeypatch.setattr(webhook_service.socket, "getaddrinfo", fake_getaddrinfo)
    assert _is_safe_webhook_url("https://example.com/hook") is True


def test_deliver_does_not_post_unsafe_url(monkeypatch):
    # _deliver must not POST to a blocked URL. httpx.post raises if called;
    # the DB UPDATE is swallowed (no live DB), which is fine for this assertion.
    def boom(*args, **kwargs):
        raise AssertionError("httpx.post must not be called for an unsafe URL")

    monkeypatch.setattr(webhook_service.httpx, "post", boom)
    # Should not raise AssertionError — the unsafe URL short-circuits before POST.
    _deliver(1, "http://169.254.169.254/steal", "secret", "lot.taken", b"{}")
