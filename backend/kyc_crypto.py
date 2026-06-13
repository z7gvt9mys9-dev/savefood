"""Encryption-at-rest for KYC identity/eligibility documents (§58).

Policy: no human routinely accesses these documents — verification is fully
automated (LLM + deterministic scoring). The raw file therefore exists in
plaintext only transiently, inside the verification process's memory. On disk it
is stored encrypted with a symmetric key (Fernet), so a stolen disk/backup or a
leaked volume does not leak identity documents.

The key (KYC_ENCRYPTION_KEY) is a Fernet key — generate one with:

    python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"

Keep it OUT of routine operators' hands (backend secret store only). Decrypting a
retained document for accountability ("кто взял еду и пропал") is a deliberate
break-glass action with that key, not part of any normal flow.

If the key is unset (local dev / CI) the module is a transparent passthrough so
the suite runs without secrets — it logs one warning so this can never be
mistaken for production behaviour.
"""
import logging
import os
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

_KEY = os.getenv("KYC_ENCRYPTION_KEY", "").strip()
_fernet: Optional[Fernet] = None
if _KEY:
    try:
        _fernet = Fernet(_KEY.encode())
    except Exception:
        # A malformed key must fail loudly rather than silently store plaintext.
        raise RuntimeError(
            "KYC_ENCRYPTION_KEY is set but is not a valid Fernet key — generate "
            "one with Fernet.generate_key()."
        )
else:
    logging.warning(
        "[kyc_crypto] KYC_ENCRYPTION_KEY is unset — KYC documents are stored "
        "UNENCRYPTED. Set it in production."
    )


def enabled() -> bool:
    return _fernet is not None


def encrypt_file(path: str) -> None:
    """Encrypt a freshly-saved upload in place. No-op (passthrough) without a key."""
    if _fernet is None:
        return
    with open(path, "rb") as f:
        data = f.read()
    token = _fernet.encrypt(data)
    # Write to a temp file then atomically replace, so a crash mid-write can't
    # leave a half-encrypted (unrecoverable) document.
    tmp = path + ".enc.tmp"
    with open(tmp, "wb") as f:
        f.write(token)
    os.replace(tmp, path)


def read_decrypted(path: str) -> bytes:
    """Read a stored document and return its plaintext bytes (in memory only).

    Tolerates a plaintext file when no key is configured, and also when a key is
    configured but the file predates encryption (migration grace) — in that case
    the bytes aren't a valid Fernet token, so we return them as-is."""
    with open(path, "rb") as f:
        data = f.read()
    if _fernet is None:
        return data
    try:
        return _fernet.decrypt(data)
    except InvalidToken:
        # Not encrypted (legacy file written before the key existed). Return raw.
        return data
