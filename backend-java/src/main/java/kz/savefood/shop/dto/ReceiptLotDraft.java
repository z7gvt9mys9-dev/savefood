package kz.savefood.shop.dto;

/** One lot draft grouped from receipt items (schemas.py ReceiptLotDraft). */
public record ReceiptLotDraft(
    String description,
    Integer quantity,
    String category) {
}
