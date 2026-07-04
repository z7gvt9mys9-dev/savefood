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

    public ShopService(JdbcTemplate jdbc, ShopRepository repo, BillingService billing,
                       NeedyService needyService, PasswordService passwords) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.billing = billing;
        this.needyService = needyService;
        this.passwords = passwords;
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
        billing.acquireLotQuota(shopId);
        return repo.createLot(shopId, description, quantity, expiryDate, photo, address, timeSlot,
            category, comment, requiresCold, unit, unitWeightKg);
    }

    /** As {@link #createLot} but with the full photo list (multi-upload form). */
    @Transactional
    public int createLotWithPhotos(int shopId, String description, double quantity, LocalDate expiryDate,
                                   List<String> photos, String address, String timeSlot, String category,
                                   String comment, boolean requiresCold, String unit, double unitWeightKg) {
        billing.acquireLotQuota(shopId);
        return repo.createLotMultiPhoto(shopId, description, quantity, expiryDate, photos, address,
            timeSlot, category, comment, requiresCold, unit, unitWeightKg);
    }

    /** Create the confirmed receipt's lots under one quota lock (routes.py {@code confirm_receipt}). */
    @Transactional
    public List<Integer> confirmReceiptLots(int shopId, int receiptId, List<ReceiptLotDraft> drafts,
                                            LocalDate expiry, String address, String timeSlot) {
        List<Integer> lotIds = new ArrayList<>();
        billing.acquireLotQuota(shopId);
        for (ReceiptLotDraft draft : drafts) {
            lotIds.add(repo.createLot(shopId, draft.description(), draft.quantity(), expiry, null,
                address, timeSlot, draft.category(), "Создано из чека #" + receiptId + " (OCR)",
                false, "кг", 1.0));
        }
        repo.confirmReceipt(receiptId, lotIds);
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
        List<Map<String, Object>> tickets = jdbc.queryForList(
            "SELECT t.* FROM tickets t JOIN lots l ON l.id = t.lot_id "
            + "WHERE t.id = ? AND l.shop_id = ?", ticketId, shopId);
        if (tickets.isEmpty()) {
            throw new ApiException(404, "Заявка не найдена или относится к другому магазину");
        }
        Map<String, Object> ticket = tickets.get(0);
        String qrSecret = (String) ticket.get("qr_secret");
        // A ticket with a secret can only be closed by presenting it; the bare
        // SF-{id} form is accepted only for legacy secret-less tickets.
        if (qrSecret != null && !qrSecret.isEmpty() && !qrSecret.equals(providedSecret)) {
            throw new ApiException(400, "Код не совпадает — отсканируйте QR получателя");
        }
        if (!Boolean.TRUE.equals(ticket.get("self_pickup"))) {
            throw new ApiException(400, "Эта заявка доставляется волонтёром, а не самовывозом");
        }
        if (!"open".equals(ticket.get("status"))) {
            throw new ApiException(400, "Заявка уже закрыта или отменена");
        }
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("UPDATE tickets SET status = 'fulfilled', fulfilled_at = ? WHERE id = ?", now, ticketId);
        int needyId = ((Number) ticket.get("needy_id")).intValue();
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
}
