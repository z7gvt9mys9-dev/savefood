"""At-rest encryption for KYC documents (§58)."""
from cryptography.fernet import Fernet

from backend import kyc_crypto


def test_roundtrip_encrypts_on_disk(tmp_path, monkeypatch):
    monkeypatch.setattr(kyc_crypto, "_fernet", Fernet(Fernet.generate_key()))
    p = tmp_path / "doc.bin"
    p.write_bytes(b"secret identity document")
    kyc_crypto.encrypt_file(str(p))
    # On disk it must no longer be the plaintext.
    assert p.read_bytes() != b"secret identity document"
    # But the verification path recovers it in memory.
    assert kyc_crypto.read_decrypted(str(p)) == b"secret identity document"


def test_passthrough_without_key(tmp_path, monkeypatch):
    monkeypatch.setattr(kyc_crypto, "_fernet", None)
    p = tmp_path / "doc.bin"
    p.write_bytes(b"plain")
    kyc_crypto.encrypt_file(str(p))  # no-op
    assert p.read_bytes() == b"plain"
    assert kyc_crypto.read_decrypted(str(p)) == b"plain"


def test_reads_legacy_plaintext_with_key(tmp_path, monkeypatch):
    """A file written before the key existed must still be readable."""
    monkeypatch.setattr(kyc_crypto, "_fernet", Fernet(Fernet.generate_key()))
    p = tmp_path / "legacy.bin"
    p.write_bytes(b"legacy plaintext")
    assert kyc_crypto.read_decrypted(str(p)) == b"legacy plaintext"
