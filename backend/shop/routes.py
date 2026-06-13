from fastapi import APIRouter, HTTPException, UploadFile, File, Form, Depends, Request
from fastapi.responses import FileResponse, Response
from typing import List, Optional
from datetime import datetime, timezone
import json
import os
import re
import psycopg2.errors

from backend.shop import db, schemas
from backend.database import create_user, get_db_cursor
from backend.needy import db as needy_db
from backend.auth import get_password_hash, get_current_user, ensure_owner_or_admin
from backend.limiter import limiter
from backend.utils import validate_and_save_upload, UploadValidationError
from backend import billing, esg, receipt_service, needs_match, forecast, webhook_service

router = APIRouter()

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads")
# Receipts are NOT publicly mounted (they carry merchant/financial details);
# the image is only reachable through the auth-checked endpoint below.
RECEIPT_DIR = os.path.join(os.path.dirname(__file__), "receipt_uploads")


def _require_lot_owner(lot_id: int, current_user: dict):
    """Return the lot if the caller owns its shop (or is admin), else 404/403."""
    lot = db.get_lot_by_id(lot_id)
    if not lot:
        raise HTTPException(status_code=404, detail="Lot not found")
    ensure_owner_or_admin(current_user, "shop", lot["shop_id"])
    return lot


@router.post("/shops/register")
@limiter.limit("5/minute")
def register_shop(request: Request, payload: schemas.ShopCreate):
    # An account is mandatory: a shop row without credentials can never log in.
    if not payload.username or not payload.password:
        raise HTTPException(status_code=400, detail="Укажите логин и пароль")
    kind = payload.kind or "business"
    if kind not in ("business", "private"):
        raise HTTPException(status_code=400, detail="kind должен быть 'business' или 'private'")
    hashed = get_password_hash(payload.password)
    # Single transaction: if the username is taken, the shop row rolls back too.
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "INSERT INTO shops (name, contact, lat, lon, city, kind, created_at) VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING id",
                (payload.name, payload.contact, payload.lat, payload.lon, payload.city, kind, datetime.now(timezone.utc)),
            )
            shop_id = cur.fetchone()['id']
            cur.execute(
                "INSERT INTO users (username, hashed_password, role, related_id) VALUES (%s, %s, %s, %s)",
                (payload.username, hashed, "shop", shop_id),
            )
    except psycopg2.errors.UniqueViolation:
        raise HTTPException(status_code=409, detail="Username already taken")
    return {"id": shop_id}


@router.post("/shops/{shop_id}/lots")
def create_lot(shop_id: int, payload: schemas.LotCreate, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")

    billing.check_lot_quota(shop_id)
    # Validation: if unit is not kg, unit_weight_kg is mandatory and must be > 0.
    unit = payload.unit or "кг"
    weight = payload.unit_weight_kg
    if unit != 'кг':
        if weight is None or weight <= 0:
            raise HTTPException(status_code=400, detail="Для единиц измерения кроме 'кг' необходимо указать вес одной единицы (кг)")
    else:
        weight = 1.0
    
    # C2C anti-abuse (§45): a private donor's lot must show the actual food —
    # recipients can't rely on a business reputation they can see on the map.
    if (shop.get("kind") == "private") and not payload.photo:
        raise HTTPException(status_code=400, detail="Для частных доноров фотография лота обязательна")
    expiry = payload.expiry_date.isoformat() if payload.expiry_date else None
    lot_id = db.create_lot(
        shop_id, payload.description, payload.quantity, expiry, payload.photo,
        payload.address, payload.time_slot, payload.category, payload.comment,
        requires_cold=bool(payload.requires_cold),
        unit=unit, unit_weight_kg=weight
    )
    # «Карта потребностей»: ping recipients whose preferences match the category.
    needs_match.start_needs_match(lot_id)
    return {"id": lot_id}


@router.post("/shops/{shop_id}/lots/upload")
def create_lot_upload(
    shop_id: int,
    description: str = Form(...),
    quantity: float = Form(...),
    unit: str = Form("кг"),
    unit_weight_kg: float = Form(1.0),
    expiry_date: Optional[str] = Form(None),
    address: Optional[str] = Form(None),
    time_slot: Optional[str] = Form(None),
    category: Optional[str] = Form(None),
    comment: Optional[str] = Form(None),
    requires_cold: bool = Form(False),
    file: Optional[UploadFile] = File(None),
    current_user: dict = Depends(get_current_user),
):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    billing.check_lot_quota(shop_id)
    
    if unit != 'кг':
        if unit_weight_kg is None or unit_weight_kg <= 0:
            raise HTTPException(status_code=400, detail="Для единиц измерения кроме 'кг' необходимо указать вес одной единицы (кг)")
    else:
        unit_weight_kg = 1.0

    if shop.get("kind") == "private" and not (file and file.filename):
        raise HTTPException(status_code=400, detail="Для частных доноров фотография лота обязательна")

    photo_url = None
    if file and file.filename:
        try:
            filename = validate_and_save_upload(file, UPLOAD_DIR)
        except UploadValidationError as exc:
            raise HTTPException(status_code=exc.status_code, detail=exc.detail)
        photo_url = f"/uploads/{filename}"

    lot_id = db.create_lot(shop_id, description, float(quantity), expiry_date, photo_url, address, time_slot, category, comment, requires_cold=bool(requires_cold), unit=unit, unit_weight_kg=unit_weight_kg)
    needs_match.start_needs_match(lot_id)
    return {"id": lot_id}


@router.get("/shops/{shop_id}/lots", response_model=List[schemas.LotOut])
def list_active_lots(shop_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    rows = db.get_active_lots(shop_id)
    return rows


@router.delete("/lots/{lot_id}")
def delete_lot(lot_id: int, current_user: dict = Depends(get_current_user)):
    _require_lot_owner(lot_id, current_user)
    ok = db.delete_lot(lot_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Lot not found or cannot be deleted")
    return {"ok": True}


@router.patch("/lots/{lot_id}", response_model=schemas.LotOut)
def patch_lot(lot_id: int, payload: schemas.LotUpdate, current_user: dict = Depends(get_current_user)):
    lot = _require_lot_owner(lot_id, current_user)
    
    # Validation: if unit is changed or weight is changed, ensure correctness
    new_unit = payload.unit if payload.unit is not None else lot.get('unit', 'кг')
    new_weight = payload.unit_weight_kg if payload.unit_weight_kg is not None else lot.get('unit_weight_kg', 1.0)
    
    if new_unit != 'кг':
        if new_weight is None or new_weight <= 0:
            raise HTTPException(status_code=400, detail="Для единиц измерения кроме 'кг' необходимо указать вес одной единицы (кг)")
    else:
        new_weight = 1.0

    expiry = payload.expiry_date.isoformat() if payload.expiry_date else None
    updated = db.update_lot(
        lot_id, payload.description, payload.quantity, expiry, payload.address,
        payload.category, payload.comment, requires_cold=payload.requires_cold,
        unit=payload.unit, unit_weight_kg=new_weight
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Lot not found or cannot be updated")
    return updated


@router.post("/lots/{lot_id}/confirm_transfer")
def confirm_transfer(lot_id: int, current_user: dict = Depends(get_current_user)):
    lot = _require_lot_owner(lot_id, current_user)
    ok = db.confirm_lot_transfer(lot_id)
    if not ok:
        raise HTTPException(status_code=400, detail="Lot not found or not in taken status")
    try:
        webhook_service.fire(lot["shop_id"], "lot.confirmed", {
            "lot_id": lot_id,
            "description": lot.get("description"),
            "quantity": lot.get("quantity"),
        })
    except Exception:
        pass
    return {"ok": True}


@router.get("/shops/{shop_id}/history", response_model=List[schemas.LotOut])
def get_history(shop_id: int, limit: int = 20, offset: int = 0, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    rows = db.get_history(shop_id, limit=limit, offset=offset)
    return rows


@router.get("/shops/{shop_id}", response_model=schemas.ShopOut)
def get_shop(shop_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    return shop


@router.patch("/shops/{shop_id}", response_model=schemas.ShopOut)
def patch_shop(shop_id: int, payload: schemas.ShopUpdate, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    updated = db.update_shop(shop_id, payload.name, payload.contact, payload.lat, payload.lon, payload.city)
    if not updated:
        raise HTTPException(status_code=404, detail="Shop not found or cannot be updated")
    return updated


@router.get("/shops/{shop_id}/notifications", response_model=List[schemas.NotificationOut])
def notifications(shop_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    notes = db.get_notifications(shop_id)
    return notes


@router.patch("/shops/notifications/{notification_id}/read")
def mark_notification_read(notification_id: int, current_user: dict = Depends(get_current_user)):
    note = db.get_notification_by_id(notification_id)
    if not note:
        raise HTTPException(status_code=404, detail="Notification not found")
    ensure_owner_or_admin(current_user, "shop", note.get("shop_id"))
    db.mark_notification_read(notification_id)
    return {"ok": True}


def _receipt_to_out(row: dict) -> dict:
    """DB row → ReceiptOut payload (items JSON unpacked, drafts regrouped)."""
    items = json.loads(row.get("items") or "[]")
    score = row.get("fraud_score") or 0.0
    return {
        "id": row["id"],
        "status": row["status"],
        "merchant": row.get("merchant"),
        "receipt_date": row.get("receipt_date"),
        "total": row.get("total"),
        "currency": row.get("currency"),
        "items": items,
        "fraud_score": row.get("fraud_score"),
        "fraud_reasons": row.get("fraud_reasons"),
        "fraud_flagged": score >= receipt_service.FRAUD_FLAG_THRESHOLD,
        "suggested_lots": receipt_service.suggest_lots(items) if row["status"] == "parsed" else [],
        "lot_ids": json.loads(row.get("lot_ids") or "[]"),
        "created_at": row.get("created_at"),
    }


@router.post("/shops/{shop_id}/receipts", response_model=schemas.ReceiptOut)
@limiter.limit("10/minute")
def upload_receipt(
    request: Request,
    shop_id: int,
    file: UploadFile = File(...),
    current_user: dict = Depends(get_current_user),
):
    """OCR pipeline: photo → parsed items + anti-fraud verdict (no lots yet —
    the shop reviews the draft and confirms via /receipts/{id}/confirm)."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    billing.require_feature(shop_id, "ocr")

    try:
        filename = validate_and_save_upload(file, RECEIPT_DIR)
    except UploadValidationError as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.detail)
    path = os.path.join(RECEIPT_DIR, filename)
    with open(path, "rb") as f:
        content = f.read()

    # Cheapest check first: byte-identical photo already uploaded (any shop).
    sha = receipt_service.sha256_hex(content)
    if db.find_receipt_by_sha(sha):
        os.remove(path)
        raise HTTPException(status_code=409, detail="Этот чек уже загружался (идентичное фото)")

    parsed = receipt_service.parse_receipt_image(content, file.content_type or "image/jpeg")
    if parsed is None:
        os.remove(path)
        raise HTTPException(status_code=503, detail="Распознавание временно недоступно, попробуйте позже или создайте лот вручную")
    if not parsed["is_receipt"]:
        os.remove(path)
        raise HTTPException(status_code=400, detail="На фото не распознан кассовый чек")
    if not parsed["items"]:
        os.remove(path)
        raise HTTPException(status_code=400, detail="В чеке не найдено продуктовых позиций")

    fp = receipt_service.fingerprint(parsed)
    fp_dupe = bool(fp and db.fingerprint_exists(fp))
    fraud = receipt_service.evaluate_fraud(parsed, fp_dupe)
    status = "rejected" if fraud["rejected"] else "parsed"
    receipt_id = db.create_receipt(shop_id, f"/receipts/{filename}", sha, fp, parsed, fraud, status)

    try:
        webhook_service.fire(shop_id, "receipt.parsed", {
            "receipt_id": receipt_id,
            "status": status,
            "fraud_score": fraud["score"],
            "items_count": len(parsed["items"]),
            "total": parsed.get("total"),
        })
    except Exception:
        pass

    row = db.get_receipt_by_id(receipt_id)
    return _receipt_to_out(row)


@router.post("/shops/{shop_id}/receipts/{receipt_id}/confirm")
def confirm_receipt(
    shop_id: int,
    receipt_id: int,
    payload: schemas.ReceiptConfirm,
    current_user: dict = Depends(get_current_user),
):
    """The shop confirmed (possibly edited) the lot drafts — create the lots."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    # Re-gate here, not only at upload: between OCR upload and confirmation the
    # plan may have been downgraded — confirm must not mint lots past billing.
    billing.require_feature(shop_id, "ocr")
    billing.check_lot_quota(shop_id)
    receipt = db.get_receipt_by_id(receipt_id)
    if not receipt or receipt["shop_id"] != shop_id:
        raise HTTPException(status_code=404, detail="Чек не найден")
    if receipt["status"] == "rejected":
        raise HTTPException(status_code=400, detail=f"Чек отклонён антифродом: {receipt.get('fraud_reasons') or ''}")
    if receipt["status"] == "confirmed":
        raise HTTPException(status_code=400, detail="Лоты по этому чеку уже созданы")

    shop = db.get_shop_by_id(shop_id)
    expiry = payload.expiry_date.isoformat() if payload.expiry_date else None
    address = payload.address or shop.get("city")
    lot_ids = []
    for draft in payload.lots:
        if draft.category not in receipt_service.LOT_CATEGORIES:
            raise HTTPException(status_code=400, detail=f"Неизвестная категория: {draft.category}")
        lot_ids.append(db.create_lot(
            shop_id, draft.description, draft.quantity, expiry, None,
            address, payload.time_slot, draft.category,
            f"Создано из чека #{receipt_id} (OCR)",
        ))
    db.confirm_receipt(receipt_id, lot_ids)
    for lid in lot_ids:
        needs_match.start_needs_match(lid)
    return {"ok": True, "lot_ids": lot_ids}


@router.get("/shops/{shop_id}/receipts", response_model=List[schemas.ReceiptOut])
def list_receipts(shop_id: int, limit: int = 20, offset: int = 0, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    if not db.get_shop_by_id(shop_id):
        raise HTTPException(status_code=404, detail="Shop not found")
    return [_receipt_to_out(r) for r in db.get_receipts(shop_id, limit=limit, offset=offset)]


@router.get("/shops/{shop_id}/receipts/{receipt_id}/image")
def get_receipt_image(shop_id: int, receipt_id: int, current_user: dict = Depends(get_current_user)):
    """Receipt photos are not on a public mount — owner/admin only."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    receipt = db.get_receipt_by_id(receipt_id)
    if not receipt or receipt["shop_id"] != shop_id or not receipt.get("photo"):
        raise HTTPException(status_code=404, detail="Чек не найден")
    filename = os.path.basename(receipt["photo"])
    path = os.path.join(RECEIPT_DIR, filename)
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="Файл не найден")
    return FileResponse(path)


@router.get("/shops/{shop_id}/forecast")
def get_forecast(shop_id: int, current_user: dict = Depends(get_current_user)):
    """Прогноз списаний: average write-off kg per category for today/tomorrow,
    so the shop can publish «future write-off» lots a few hours in advance."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    if not db.get_shop_by_id(shop_id):
        raise HTTPException(status_code=404, detail="Shop not found")
    return forecast.shop_forecast(shop_id)


@router.get("/shops/{shop_id}/plan")
def get_plan(shop_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    if not db.get_shop_by_id(shop_id):
        raise HTTPException(status_code=404, detail="Shop not found")
    return billing.plan_summary(shop_id)


@router.get("/shops/{shop_id}/esg")
def get_esg_report(shop_id: int, months: int = 12, current_user: dict = Depends(get_current_user)):
    """ESG impact report (kg rescued, CO2 prevented, meals) — «Профи» and up."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    if not db.get_shop_by_id(shop_id):
        raise HTTPException(status_code=404, detail="Shop not found")
    billing.require_feature(shop_id, "esg")
    return esg.shop_report(shop_id, months=months)


@router.get("/shops/{shop_id}/esg/report.csv")
def get_esg_report_csv(shop_id: int, months: int = 12, current_user: dict = Depends(get_current_user)):
    """«Налоговый помощник» (§50): the ESG report as a CSV the shop can attach to
    a charitable-donation tax filing (specific to KZ). Same data and gating as
    the JSON ESG endpoint; the CSV format is dependency-free and Excel-friendly."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    billing.require_feature(shop_id, "esg")
    report = esg.shop_report(shop_id, months=months)
    csv_text = esg.report_to_csv(report, shop_name=shop.get("name") or f"shop-{shop_id}")
    filename = f"savefood_donations_shop{shop_id}_{months}m.csv"
    # UTF-8 BOM so Excel opens Cyrillic correctly.
    return Response(
        content="﻿" + csv_text,
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.post("/shops/{shop_id}/self_pickup/confirm")
@limiter.limit("20/minute")
def confirm_self_pickup(shop_id: int, payload: schemas.SelfPickupConfirm, request: Request, current_user: dict = Depends(get_current_user)):
    """The shop scans/types the recipient's QR code (SF-{ticket_id}-{secret}) to
    close a self-pickup ticket. Without this, self-pickup tickets stay 'open'
    forever and block the recipient from creating any new ticket.

    The QR secret + the rate limit stop a shop from brute-forcing SF-{id} codes
    to mark its own lots' tickets as picked up (which would burn the recipient's
    weekly limit and inflate the shop's ESG)."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    # Case-sensitive secret (url-safe base64) — only the "SF" prefix is lenient.
    match = re.fullmatch(r"SF-(\d+)(?:-([A-Za-z0-9_-]+))?", (payload.code or "").strip(), re.IGNORECASE)
    if not match:
        raise HTTPException(status_code=400, detail="Неверный формат кода (ожидается SF-<номер>-<код>)")
    ticket_id = int(match.group(1))
    provided_secret = match.group(2)

    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT t.* FROM tickets t
            JOIN lots l ON l.id = t.lot_id
            WHERE t.id = %s AND l.shop_id = %s
            """,
            (ticket_id, shop_id),
        )
        ticket = cur.fetchone()
        if not ticket:
            raise HTTPException(status_code=404, detail="Заявка не найдена или относится к другому магазину")
        # A ticket with a secret can only be closed by presenting that secret —
        # the bare SF-{id} form is accepted only for legacy secret-less tickets.
        if ticket.get("qr_secret") and provided_secret != ticket["qr_secret"]:
            raise HTTPException(status_code=400, detail="Код не совпадает — отсканируйте QR получателя")
        if not ticket["self_pickup"]:
            raise HTTPException(status_code=400, detail="Эта заявка доставляется волонтёром, а не самовывозом")
        if ticket["status"] != "open":
            raise HTTPException(status_code=400, detail="Заявка уже закрыта или отменена")
        now = datetime.now(timezone.utc)
        cur.execute(
            "UPDATE tickets SET status = 'fulfilled', fulfilled_at = %s WHERE id = %s",
            (now, ticket_id),
        )
        cur.execute(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
            (ticket["needy_id"], 'self_pickup_confirmed',
             f'Самовывоз по заявке #{ticket_id} подтверждён магазином. Спасибо!', now),
        )
    try:
        needy_db.set_profile_last_received(ticket["needy_id"], now)
    except Exception:
        pass
    return {"ok": True, "ticket_id": ticket_id}
