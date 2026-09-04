package ru.savefood.shop.dto;
import java.time.LocalDate;
import java.util.List;
/** POST /shops/{id}/receipts/{rid}/confirm body (schemas.py ReceiptConfirm). */
public record ReceiptConfirm(
    List<ReceiptLotDraft> lots,
    LocalDate expiryDate,
    String address,
    String timeSlot) {
}
