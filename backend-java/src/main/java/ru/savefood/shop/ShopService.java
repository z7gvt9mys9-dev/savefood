package ru.savefood.shop;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.savefood.billing.BillingService;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.dto.ReceiptLotDraft;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ShopService {
    private final JdbcTemplate jdbc;
    private final ShopRepository repo;
    private final BillingService billing;
    private final NeedyService needyService;
    private final PasswordService passwords;
    private final UploadService uploads;
    private final LotUploadCleanup lotUploadCleanup;
    public ShopService(JdbcTemplate jdbc, ShopRepository repo, BillingService billing,
                       NeedyService needyService, PasswordService passwords, UploadService uploads,
                       LotUploadCleanup lotUploadCleanup) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.billing = billing;
        this.needyService = needyService;
        this.passwords = passwords;
        this.uploads = uploads;
        this.lotUploadCleanup = lotUploadCleanup;
    }
    @Transactional
    public int registerShop(String name, String contact, Double lat, Double lon, String city,
                            String kind, String username, String rawPassword) {
        String hashed = passwords.hash(rawPassword);
        Integer shopId;
        try {
            shopId = jdbc.queryForObject(
                "INSERT INTO shops (name, contact, lat, lon, city, kind, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Integer.class, name, contact, lat, lon, city, kind, OffsetDateTime.now());
            jdbc.update(
                "INSERT INTO users (username, hashed_password, role, related_id) VALUES (?, ?, 'shop', ?)",
                username, hashed, shopId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Username already taken");
        }
        return shopId;
    }
    /** Quota-guarded lot creation (routes.py {@code create_lot} + lot_quota_guard). */
    @Transactional
    public int createLot(int shopId, String description, double quantity, LocalDate expiryDate,
                         String photo, String address, String timeSlot, String category,
                         String comment, boolean requiresCold, String unit, double unitWeightKg) {
        LotQuantity.requireWholeUnits(quantity, "quantity");
        requirePositiveFinite(unitWeightKg, "unit_weight_kg");
        billing.acquireLotQuota(shopId);
        return repo.createLot(shopId, description, quantity, expiryDate, photo, address, timeSlot,
            category, comment, requiresCold, unit, unitWeightKg);
    }
    @Transactional
    public int createLotWithClaimedPhoto(int shopId, String description, double quantity,
                                         LocalDate expiryDate, String filename, String address,
                                         String timeSlot, String category, String comment,
                                         boolean requiresCold, String unit, double unitWeightKg) {
        LotQuantity.requireWholeUnits(quantity, "quantity");
        requirePositiveFinite(unitWeightKg, "unit_weight_kg");
        billing.acquireLotQuota(shopId);
        int lotId = repo.createLot(shopId, description, quantity, expiryDate, "/uploads/" + filename,
            address, timeSlot, category, comment, requiresCold, unit, unitWeightKg);
        if (!repo.claimLotPhotoUpload(shopId, filename, lotId)) {
            throw new ApiException(400, "Фотография лота недействительна или уже использована");
        }
        return lotId;
    }
    /** As {@link #createLot} but with the full photo list (multi-upload form). */
    @Transactional
    public int createLotWithPhotos(int shopId, String description, double quantity, LocalDate expiryDate,
                                   List<String> photos, String address, String timeSlot, String category,
                                   String comment, boolean requiresCold, String unit, double unitWeightKg) {
        LotQuantity.requireWholeUnits(quantity, "quantity");
        requirePositiveFinite(unitWeightKg, "unit_weight_kg");
        billing.acquireLotQuota(shopId);
        return repo.createLotMultiPhoto(shopId, description, quantity, expiryDate, photos, address,
            timeSlot, category, comment, requiresCold, unit, unitWeightKg);
    }
    @Transactional
    public int createLotWithPreparedPhotos(int shopId, String description, double quantity,
                                           LocalDate expiryDate,
                                           List<UploadService.PreparedUpload> preparedPhotos,
                                           String uploadDir, String address, String timeSlot,
                                           String category, String comment, boolean requiresCold,
                                           String unit, double unitWeightKg) {
        LotQuantity.requireWholeUnits(quantity, "quantity");
        requirePositiveFinite(unitWeightKg, "unit_weight_kg");
        billing.acquireLotQuota(shopId);
        List<String> created = new ArrayList<>();
        try {
            List<String> photoUrls = new ArrayList<>();
            if (preparedPhotos != null) {
                for (UploadService.PreparedUpload prepared : preparedPhotos) {
                    String filename = uploads.savePrepared(prepared, uploadDir);
                    lotUploadCleanup.deleteOnRollback(filename);
                    created.add(filename);
                    photoUrls.add("/uploads/" + filename);
                }
            }
            return repo.createLotMultiPhoto(shopId, description, quantity, expiryDate, photoUrls, address,
                timeSlot, category, comment, requiresCold, unit, unitWeightKg);
        } catch (RuntimeException e) {
            if (e instanceof UploadService.UploadWriteException writeFailure) {
                created.add(writeFailure.filename());
            }
            lotUploadCleanup.removeOrQueue(created);
            throw e;
        }
    }
    /** Create the confirmed receipt's lots under one quota lock (routes.py {@code confirm_receipt}). */
    @Transactional
    public List<Integer> confirmReceiptLots(int shopId, int receiptId, List<ReceiptLotDraft> drafts,
                                            LocalDate expiry, String address, String timeSlot) {
        Map<String, Object> receipt = repo.getReceiptForUpdate(receiptId);
        if (receipt == null || ((Number) receipt.get("shop_id")).intValue() != shopId) {
            throw new ApiException(404, "Чек не найден");
        }
        String status = (String) receipt.get("status");
        if ("rejected".equals(status)) {
            throw new ApiException(400, "Чек отклонён антифродом");
        }
        if ("confirmed".equals(status)) {
            throw new ApiException(409, "Лоты по этому чеку уже созданы");
        }
        if (!"parsed".equals(status)) {
            throw new ApiException(409, "Чек ещё не готов к подтверждению");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw new ApiException(422, "lots: список не может быть пустым");
        }
        for (ReceiptLotDraft draft : drafts) {
            if (draft == null) {
                throw new ApiException(422, "quantity каждого лота должна быть целым числом не меньше 1");
            }
            LotQuantity.requireWholeUnits(draft.quantity(), "quantity каждого лота");
        }
        List<Integer> lotIds = new ArrayList<>();
        billing.acquireLotQuota(shopId, drafts.size());
        for (ReceiptLotDraft draft : drafts) {
            lotIds.add(repo.createLot(shopId, draft.description(), draft.quantity(), expiry, null,
                address, timeSlot, draft.category(), "Создано из чека #" + receiptId + " (OCR)",
                false, "кг", 1.0));
        }
        if (!repo.confirmReceipt(receiptId, lotIds)) {
            throw new ApiException(409, "Лоты по этому чеку уже созданы");
        }
        return lotIds;
    }
    @Transactional
    public boolean deleteLot(int lotId) {
        return repo.deleteLot(lotId);
    }
    @Transactional
    public int confirmSelfPickup(int shopId, int ticketId, String providedSecret) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Integer> winners = jdbc.query(
            "UPDATE tickets AS t SET status = 'fulfilled', fulfilled_at = ? FROM lots AS l "
            + "WHERE t.id = ? AND t.lot_id = l.id AND l.shop_id = ? "
            + "AND t.status = 'open' AND t.self_pickup IS TRUE "
            + "AND (t.expires_at IS NULL OR t.expires_at > clock_timestamp()) "
            + "AND (t.qr_secret IS NULL OR t.qr_secret = '' OR t.qr_secret = ?) "
            + "RETURNING t.needy_id",
            (rs, rowNum) -> rs.getInt("needy_id"), now, ticketId, shopId, providedSecret);
        if (winners.isEmpty()) {
            throw explainSelfPickupRejection(shopId, ticketId, providedSecret);
        }
        int needyId = winners.get(0);
        jdbc.update(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
            needyId, "self_pickup_confirmed",
            "Самовывоз по заявке #" + ticketId + " подтверждён магазином. Спасибо!", now);
        try {
            needyService.setProfileLastReceived(needyId, now);
        } catch (RuntimeException ignored) {
        }
        return ticketId;
    }
    /** Preserve the existing API errors after the atomic winner statement loses. */
    private ApiException explainSelfPickupRejection(int shopId, int ticketId, String providedSecret) {
        List<Map<String, Object>> tickets = jdbc.queryForList(
            "SELECT t.status, t.self_pickup, t.qr_secret, "
            + "(t.expires_at IS NOT NULL AND t.expires_at <= clock_timestamp()) AS expired "
            + "FROM tickets t JOIN lots l ON l.id = t.lot_id "
            + "WHERE t.id = ? AND l.shop_id = ?", ticketId, shopId);
        if (tickets.isEmpty()) {
            return new ApiException(404, "Заявка не найдена или относится к другому магазину");
        }
        Map<String, Object> ticket = tickets.get(0);
        String qrSecret = (String) ticket.get("qr_secret");
        if (qrSecret != null && !qrSecret.isEmpty() && !qrSecret.equals(providedSecret)) {
            return new ApiException(400, "Код не совпадает — отсканируйте QR получателя");
        }
        if (!Boolean.TRUE.equals(ticket.get("self_pickup"))) {
            return new ApiException(400, "Эта заявка доставляется волонтёром, а не самовывозом");
        }
        if (!"open".equals(ticket.get("status"))) {
            return new ApiException(400, "Заявка уже закрыта или отменена");
        }
        if (Boolean.TRUE.equals(ticket.get("expired"))) {
            return new ApiException(400, "Срок брони истёк");
        }
        return new ApiException(409, "Заявка уже изменилась — повторите попытку");
    }
    private static void requirePositiveFinite(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new ApiException(422, field + ": значение должно быть положительным и конечным");
        }
    }
}
