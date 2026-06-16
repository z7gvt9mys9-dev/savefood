import io
import os

import pytest
from PIL import Image

from datetime import datetime, timedelta, timezone

from backend.utils import (
    MAX_UPLOAD_BYTES,
    UploadValidationError,
    calculate_priority_score,
    ensure_aware_utc,
    haversine,
    is_within_weekly_limit,
    validate_and_save_upload,
)


def test_haversine_known_distance():
    # Almaty centre → ~1 degree of latitude ≈ 111 km
    d = haversine(43.0, 76.9, 44.0, 76.9)
    assert 110_000 < d < 112_000


def test_haversine_zero():
    assert haversine(43.25, 76.95, 43.25, 76.95) == 0


def test_priority_score_family_and_urgency():
    base = calculate_priority_score(1, False, datetime.now(timezone.utc))
    bigger_family = calculate_priority_score(4, False, datetime.now(timezone.utc))
    urgent = calculate_priority_score(1, True, datetime.now(timezone.utc))
    assert bigger_family == base + 30
    assert urgent == base + 50


def test_priority_score_never_helped_bonus():
    helped_today = calculate_priority_score(1, False, datetime.now(timezone.utc))
    never_helped = calculate_priority_score(1, False, None)
    assert never_helped == helped_today + 100


def test_weekly_limit():
    now = datetime.now(timezone.utc)
    assert is_within_weekly_limit(None) is True
    assert is_within_weekly_limit(now - timedelta(days=8)) is True
    assert is_within_weekly_limit(now - timedelta(days=2)) is False


def test_ensure_aware_utc():
    naive = datetime(2026, 1, 1, 12, 0)
    aware = ensure_aware_utc(naive)
    assert aware.tzinfo is timezone.utc
    assert ensure_aware_utc(None) is None
    already = datetime(2026, 1, 1, tzinfo=timezone.utc)
    assert ensure_aware_utc(already) is already


class FakeUpload:
    def __init__(self, filename, data, content_type):
        self.filename = filename
        self.content_type = content_type
        self.file = io.BytesIO(data)


def _jpeg_with_exif() -> bytes:
    img = Image.new("RGB", (60, 40), "red")
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation: rotate 90° CW
    exif[0x010F] = "TestCam"  # Make — stands in for GPS and the rest of the block
    buf = io.BytesIO()
    img.save(buf, format="JPEG", exif=exif)
    return buf.getvalue()


def _webp_with_exif() -> bytes:
    exif = Image.Exif()
    exif[0x010F] = "TestCam"
    buf = io.BytesIO()
    Image.new("RGB", (60, 40), "green").save(buf, format="WEBP", exif=exif.tobytes())
    return buf.getvalue()


def test_upload_strips_exif_and_bakes_orientation(tmp_path):
    fn = validate_and_save_upload(
        FakeUpload("photo.jpg", _jpeg_with_exif(), "image/jpeg"), str(tmp_path)
    )
    saved = Image.open(tmp_path / fn)
    assert dict(saved.getexif()) == {}
    # Orientation tag must be applied to the pixels, not just dropped
    assert saved.size == (40, 60)


def test_upload_strips_exif_from_webp(tmp_path):
    # WEBP matters separately: Pillow's encoder re-attaches exif from img.info
    fn = validate_and_save_upload(
        FakeUpload("photo.webp", _webp_with_exif(), "image/webp"), str(tmp_path)
    )
    assert dict(Image.open(tmp_path / fn).getexif()) == {}


def test_upload_random_filename(tmp_path):
    fn = validate_and_save_upload(
        FakeUpload("my secret name.jpg", _jpeg_with_exif(), "image/jpeg"), str(tmp_path)
    )
    assert "secret" not in fn and fn.endswith(".jpg")


def test_upload_rejects_corrupted_image(tmp_path):
    with pytest.raises(UploadValidationError) as exc:
        validate_and_save_upload(
            FakeUpload("photo.jpg", b"not an image at all", "image/jpeg"), str(tmp_path)
        )
    assert exc.value.status_code == 400


def test_upload_rejects_bad_extension_and_type(tmp_path):
    with pytest.raises(UploadValidationError) as exc:
        validate_and_save_upload(
            FakeUpload("run.exe", b"MZ", "application/octet-stream"), str(tmp_path)
        )
    assert exc.value.status_code == 415
    with pytest.raises(UploadValidationError) as exc:
        validate_and_save_upload(
            FakeUpload("doc.pdf", b"%PDF-1.4", "application/pdf"), str(tmp_path)
        )
    assert exc.value.status_code == 415  # pdf only with allow_pdf=True


def test_upload_rejects_oversize(tmp_path):
    big = b"\xff" * (MAX_UPLOAD_BYTES + 1)
    with pytest.raises(UploadValidationError) as exc:
        validate_and_save_upload(FakeUpload("big.jpg", big, "image/jpeg"), str(tmp_path))
    assert exc.value.status_code == 413


def test_upload_pdf_passthrough_when_allowed(tmp_path):
    raw = b"%PDF-1.4 fake body"
    fn = validate_and_save_upload(
        FakeUpload("doc.pdf", raw, "application/pdf"), str(tmp_path), allow_pdf=True
    )
    assert (tmp_path / fn).read_bytes() == raw


def test_upload_rejects_pdf_without_magic_bytes(tmp_path):
    # A .pdf/application/pdf upload whose bytes are not a real PDF must be rejected.
    with pytest.raises(UploadValidationError) as exc:
        validate_and_save_upload(
            FakeUpload("doc.pdf", b"<html>not a pdf</html>", "application/pdf"),
            str(tmp_path),
            allow_pdf=True,
        )
    assert exc.value.status_code == 415


def test_upload_accepts_valid_pdf_magic_bytes(tmp_path):
    raw = b"%PDF-1.4\n%\xe2\xe3\xcf\xd3 real-ish pdf body"
    fn = validate_and_save_upload(
        FakeUpload("doc.pdf", raw, "application/pdf"), str(tmp_path), allow_pdf=True
    )
    assert (tmp_path / fn).read_bytes() == raw
