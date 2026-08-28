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

/**
 * Transactional shop mutations that span more than one statement or need the
 * monthly-quota advisory lock held across an insert — the Java home for the work
 * the Python routes did inside a single {@code get_db_cursor()} block or
 * {@code billing.lot_quota_guard}. Single-statement reads/writes stay on
 * {@link ShopRepository}; the fire-and-forget {@code needs_match} fan-out is
 * triggered by the controller after these methods commit.
 */
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

    /**
     * Register a shop + its login in one transaction (routes.py {@code register_shop}):
     * if the username is taken, the shop row rolls back too.
     */
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

    /**
     * Creates a private-donor lot and consumes its already validated, owner-bound
     * staged image in the same transaction.  The conditional claim prevents a
     * reference from being attached to two lots under concurrent requests.
     */
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

    /**
     * Creates a multipart lot under the same transaction as its quota guard.
     * Prepared uploads have already passed all size, decoder and image-bomb
     * checks, but are not written until quota is available.  Every generated
     * filename is request-local and is removed (or durably queued for removal)
     * if a later write or database operation fails.
     */
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
        // Serialise competing confirms on the receipt row itself. The controller's
        // earlier read is only a friendly fast-fail; this locked read is the
        // authoritative check that prevents duplicate lots.
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

    /**
     * Close a self-pickup ticket by the recipient's QR code (routes.py
     * {@code confirm_self_pickup}). Ownership is checked by the caller; here the
     * ticket is validated, marked fulfilled, the recipient notified, and their
     * "last received" stamp refreshed. Returns the closed ticket id.
     */
    @Transactional
    public int confirmSelfPickup(int shopId, int ticketId, String providedSecret) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Integer> winners = jdbc.query(
            "UPDATE tickets AS t SET status = 'fulfilled', fulfilled_at = ? FROM lots AS l "
            + "WHERE t.id = ? AND t.lot_id = l.id AND l.shop_id = ? "
            + "AND t.status = 'open' AND t.self_pickup IS TRUE "
            + "AND (t.expires_at IS NULL OR t.expires_at > clock_timestamp()) "
            // A ticket with a secret can only be closed by presenting it; the bare
            // SF-{id} form remains valid only for legacy secret-less tickets.
            + "AND (t.qr_secret IS NULL OR t.qr_secret = '' OR t.qr_secret = ?) "
            + "RETURNING t.needy_id",
            (rs, rowNum) -> rs.getInt("needy_id"), now, ticketId, shopId, providedSecret);
        if (winners.isEmpty()) {
            throw explainSelfPickupRejection(shopId, ticketId, providedSecret);
        }

        // PostgreSQL rechecks every guard after waiting for a concurrent writer.
        // Therefore only the request represented by this returned row may emit
        // fulfilment side effects.
        int needyId = winners.get(0);
        jdbc.update(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
            needyId, "self_pickup_confirmed",
            "Самовывоз по заявке #" + ticketId + " подтверждён магазином. Спасибо!", now);
        try {
            needyService.setProfileLastReceived(needyId, now);
        } catch (RuntimeException ignored) {
            // best-effort, like the Python try/except around set_profile_last_received
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
