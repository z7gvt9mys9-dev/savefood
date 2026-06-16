import math
import os
import secrets
import uuid
import io
from datetime import datetime, timezone
from typing import Optional
from PIL import Image, ImageOps

# Unambiguous alphabet for human-typed codes: no 0/O, 1/I/L.
JOIN_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
JOIN_CODE_LENGTH = 6


def generate_join_code(length: int = JOIN_CODE_LENGTH) -> str:
    """Team invite code — short enough to dictate over the phone."""
    return "".join(secrets.choice(JOIN_CODE_ALPHABET) for _ in range(length))


def generate_qr_secret() -> str:
    """Per-ticket secret baked into the delivery QR (SF-{id}-{secret}).

    The whole point is that it is NOT derivable from the ticket id: only the
    recipient (who shows the QR) and an admin ever learn it, so a volunteer
    can't mark a delivery fulfilled — or a shop close a self-pickup — without
    the recipient physically presenting the code. ~10 url-safe chars ≈ 60 bits.
    """
    return secrets.token_urlsafe(8)


def build_qr_code(ticket_id: int, qr_secret: Optional[str]) -> str:
    """Canonical QR payload for a ticket. Falls back to the legacy secret-less
    form for any pre-migration ticket whose secret was never set."""
    return f"SF-{ticket_id}-{qr_secret}" if qr_secret else f"SF-{ticket_id}"


# ~550 m of latitude: enough to pick a direction on the map, not enough to
# identify the building. Recipients' exact homes are revealed only to the
# volunteer whose route was actually assigned to them.
COARSE_GRID_DEG = 0.005


def coarsen_coord(value: float, grid: float = COARSE_GRID_DEG) -> float:
    """Snap a coordinate to a coarse grid (anti-doxxing for open requests)."""
    return round(round(value / grid) * grid, 6)

# Allowed MIME types and file extensions for uploaded files.
ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/jpg", "image/png", "image/webp"}
ALLOWED_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}
ALLOWED_DOC_TYPES = ALLOWED_IMAGE_TYPES | {"application/pdf"}
ALLOWED_DOC_EXTS = ALLOWED_IMAGE_EXTS | {".pdf"}
MAX_UPLOAD_BYTES = 5 * 1024 * 1024  # 5 MB


class UploadValidationError(Exception):
    def __init__(self, status_code: int, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


def validate_and_save_upload(file, dest_dir: str, *, allow_pdf: bool = False) -> str:
    """Validate MIME type, extension and size, then save under a random filename.
    Returns the new filename (without dir). Raises UploadValidationError on failure.
    """
    if file is None or not getattr(file, "filename", None):
        raise UploadValidationError(400, "No file uploaded")

    allowed_types = ALLOWED_DOC_TYPES if allow_pdf else ALLOWED_IMAGE_TYPES
    allowed_exts = ALLOWED_DOC_EXTS if allow_pdf else ALLOWED_IMAGE_EXTS

    content_type = (file.content_type or "").lower()
    if content_type and content_type not in allowed_types:
        raise UploadValidationError(415, f"Unsupported file type: {content_type}")

    ext = os.path.splitext(file.filename or "")[1].lower()
    if ext not in allowed_exts:
        raise UploadValidationError(415, f"Unsupported file extension: {ext or '<none>'}")

    content = file.file.read(MAX_UPLOAD_BYTES + 1)
    if len(content) > MAX_UPLOAD_BYTES:
        raise UploadValidationError(413, f"File too large (limit {MAX_UPLOAD_BYTES // (1024 * 1024)} MB)")

    # Magic-byte check for PDFs: a .pdf extension / application/pdf content-type
    # alone is attacker-controlled. Real PDFs start with "%PDF". Images on the
    # doc path are re-encoded below (the image branch), so they self-validate.
    if allow_pdf and (ext == ".pdf" or content_type == "application/pdf"):
        if content[:4] != b"%PDF":
            raise UploadValidationError(415, "File is not a valid PDF")

    # Strip metadata (privacy): smartphone photos carry GPS in EXIF, and some of
    # these images end up on the public impact feed. Re-encode so nothing survives.
    if not allow_pdf:
        try:
            img = Image.open(io.BytesIO(content))
            fmt = img.format  # transpose returns a copy whose .format is None
            # Bake EXIF orientation into the pixels, then drop the metadata:
            # WEBP/PNG encoders silently re-attach exif/icc/xmp from img.info.
            img = ImageOps.exif_transpose(img)
            img.info = {}
            output = io.BytesIO()
            img.save(output, format=fmt)
            content = output.getvalue()
        except Exception:
            raise UploadValidationError(400, "Invalid or corrupted image")

    os.makedirs(dest_dir, exist_ok=True)
    safe_name = f"{uuid.uuid4().hex}{ext}"
    with open(os.path.join(dest_dir, safe_name), "wb") as out:
        out.write(content)
    return safe_name

def ensure_aware_utc(dt):
    if dt is None:
        return None
    if hasattr(dt, 'tzinfo') and dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt

def haversine(lat1, lon1, lat2, lon2):
    """
    Calculate the great-circle distance between two points 
    on the Earth in meters.
    """
    R = 6371000  # Earth radius in meters
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    
    a = math.sin(dphi / 2)**2 + \
        math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2)**2
    
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def calculate_priority_score(family_size: int, is_urgent: bool, last_delivery_date: datetime):
    score = family_size * 10
    if is_urgent:
        score += 50
    if last_delivery_date:
        last_aware = ensure_aware_utc(last_delivery_date)
        score += (datetime.now(timezone.utc) - last_aware).days * 5
    else:
        score += 100
    return score

def is_within_weekly_limit(last_delivery_date: datetime):
    if not last_delivery_date:
        return True
    last_aware = ensure_aware_utc(last_delivery_date)
    return (datetime.now(timezone.utc) - last_aware).days >= 7
