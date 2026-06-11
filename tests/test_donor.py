"""C2C private donor: schema defaults — the route-level photo rule is enforced
in shop/routes.py (400 for a private donor's lot without a photo)."""
from backend.shop.schemas import LotOut, ShopCreate, ShopOut


def test_shop_create_defaults_to_business():
    payload = ShopCreate(name="Магазин")
    assert payload.kind == "business"


def test_shop_create_accepts_private():
    payload = ShopCreate(name="Иван", kind="private")
    assert payload.kind == "private"


def test_lot_out_carries_shop_kind():
    assert "shop_kind" in LotOut.model_fields
    assert "kind" in ShopOut.model_fields
