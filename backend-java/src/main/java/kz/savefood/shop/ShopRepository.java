package kz.savefood.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Port of the backend/shop/db.py functions used by the shop routes, on
 * {@link JdbcTemplate} (raw SQL, mirroring the psycopg2 style). Response shapes
 * for {@code LotOut} / {@code ShopOut} / {@code NotificationOut} are built
 * explicitly so the JSON matches the pydantic {@code response_model} field set
 * (e.g. lots expose {@code shop_name} but not the internal {@code city}).
 *
 * <p>Schema creation ({@code init_db}) and the lot helpers owned by other modules
 * ({@code get_all_active_lots}, {@code take_lot}, {@code release_lot},
 * {@code expire_soon_lots}) are intentionally not ported here — the Postgres
 * schema is shared and managed by the Python migrations.
 */
@Repository
public class ShopRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ShopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Lots ──────────────────────────────────────────────────────────────────

    /** Raw lot row (all columns) for internal ownership/transition checks. */
    public Map<String, Object> getLotById(int lotId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM lots WHERE id = ?", lotId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int createLot(int shopId, String description, double quantity, LocalDate expiryDate,
                         String photo, String address, String timeSlot, String category,
                         String comment, boolean requiresCold, String unit, double unitWeightKg) {
        String city = jdbc.query("SELECT city FROM shops WHERE id = ?",
            (rs, n) -> rs.getString("city"), shopId).stream().findFirst().orElse(null);
        Integer id = jdbc.queryForObject(
            "INSERT INTO lots (shop_id, description, quantity, initial_quantity, unit, unit_weight_kg, "
            + "expiry_date, photo, address, time_slot, category, comment, city, requires_cold, status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?) RETURNING id",
            Integer.class,
            shopId, description, quantity, quantity, unit, unitWeightKg,
            expiryDate, photo, address, timeSlot, category, comment, city, requiresCold,
            OffsetDateTime.now());
        return id;
    }

    /**
     * The public active-lots map (db.py {@code get_all_active_lots}) — every shop's
     * still-available lot, joined with its shop's name/coords/kind. Optional
     * case-insensitive category and description/address search filters. Drives the
     * recipient map served by {@code GET /lots}.
     */
    public List<Map<String, Object>> getAllActiveLots(int limit, int offset, String category, String search) {
        StringBuilder sql = new StringBuilder(
            "SELECT l.*, s.name AS shop_name, s.lat AS shop_lat, s.lon AS shop_lon, s.kind AS shop_kind "
            + "FROM lots l JOIN shops s ON s.id = l.shop_id "
            + "WHERE l.status = 'active' AND l.quantity > 0 "
            + "AND (l.expiry_date IS NULL OR l.expiry_date > CURRENT_DATE + INTERVAL '1 day')");
        List<Object> params = new java.util.ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND l.category ILIKE ?");
            params.add(category);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (l.description ILIKE ? OR l.address ILIKE ?)");
            params.add("%" + search + "%");
            params.add("%" + search + "%");
        }
        sql.append(" ORDER BY l.created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), LOT_OUT_WITH_SHOP, params.toArray());
    }

    /**
     * Active (quantity &gt; 0, not within a day of expiry) plus all taken lots —
     * taken lots stay visible so the shop can still confirm the hand-over.
     */
    public List<Map<String, Object>> getActiveLots(int shopId) {
        return jdbc.query(
            "SELECT * FROM lots WHERE shop_id = ? AND ("
            + "(status = 'active' AND quantity > 0 AND "
            + "(expiry_date IS NULL OR expiry_date > CURRENT_DATE + INTERVAL '1 day')) "
            + "OR status = 'taken') ORDER BY created_at DESC",
            LOT_OUT, shopId);
    }

    public List<Map<String, Object>> getHistory(int shopId, int limit, int offset) {
        return jdbc.query(
            "SELECT * FROM lots WHERE shop_id = ? "
            + "AND status IN ('taken', 'confirmed', 'expired', 'removed') "
            + "ORDER BY COALESCE(taken_at, created_at) DESC LIMIT ? OFFSET ?",
            LOT_OUT, shopId, limit, offset);
    }

    /** Returns the updated {@code LotOut} map, or {@code null} if missing/not active. */
    public Map<String, Object> updateLot(int lotId, String description, Double quantity,
                                         LocalDate expiryDate, String address, String category,
                                         String comment, Boolean requiresCold, String unit,
                                         Double unitWeightKg) {
        Map<String, Object> lot = getLotById(lotId);
        if (lot == null || !"active".equals(lot.get("status"))) {
            return null;
        }
        String newDescription = description != null ? description : (String) lot.get("description");
        double newQuantity = quantity != null ? quantity : num(lot.get("quantity"));
        LocalDate newExpiry = expiryDate != null ? expiryDate : (LocalDate) lot.get("expiry_date");
        String newAddress = address != null ? address : (String) lot.get("address");
        String newCategory = category != null ? category : (String) lot.get("category");
        String newComment = comment != null ? comment : (String) lot.get("comment");
        boolean newCold = requiresCold != null ? requiresCold : Boolean.TRUE.equals(lot.get("requires_cold"));
        String newUnit = unit != null ? unit : (String) lot.getOrDefault("unit", "кг");
        double newWeight = unitWeightKg != null ? unitWeightKg : num(lot.getOrDefault("unit_weight_kg", 1.0));

        jdbc.update(
            "UPDATE lots SET description = ?, quantity = ?, expiry_date = ?, address = ?, "
            + "category = ?, comment = ?, requires_cold = ?, unit = ?, unit_weight_kg = ? WHERE id = ?",
            newDescription, newQuantity, newExpiry, newAddress, newCategory, newComment,
            newCold, newUnit, newWeight, lotId);
        return jdbc.query("SELECT * FROM lots WHERE id = ?", LOT_OUT, lotId).stream().findFirst().orElse(null);
    }

    public boolean confirmLotTransfer(int lotId) {
        Map<String, Object> lot = getLotById(lotId);
        if (lot == null || !"taken".equals(lot.get("status"))) {
            return false;
        }
        jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ?", lotId);
        return true;
    }

    public boolean deleteLot(int lotId) {
        Map<String, Object> lot = getLotById(lotId);
        if (lot == null || !"active".equals(lot.get("status"))) {
            return false;
        }
        jdbc.update("UPDATE lots SET status = 'removed' WHERE id = ?", lotId);
        // Free recipients who reserved a unit on this lot — it can never be served now.
        cancelOpenTickets(lotId, "лот удалён магазином");
        try {
            jdbc.update(
                "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, ?, 0)",
                lot.get("shop_id"), lotId, "lot_removed", "Лот #" + lotId + " удалён магазином",
                OffsetDateTime.now());
        } catch (RuntimeException ignored) {
            // best-effort notification, like the Python except: pass
        }
        return true;
    }

    /**
     * Cancel every still-open reservation on a lot leaving the vitrine, notifying
     * each recipient that their weekly limit was not spent (db.py
     * {@code _cancel_lot_open_tickets}, audit Q5). Runs in the caller's transaction.
     */
    private void cancelOpenTickets(int lotId, String reason) {
        List<Map<String, Object>> cancelled = jdbc.queryForList(
            "UPDATE tickets SET status = 'cancelled' WHERE lot_id = ? AND status = 'open' "
            + "RETURNING id, needy_id", lotId);
        OffsetDateTime now = OffsetDateTime.now();
        for (Map<String, Object> row : cancelled) {
            jdbc.update(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, 0)",
                row.get("needy_id"), "ticket_cancelled",
                "Заявка #" + row.get("id") + " отменена: " + reason
                + ". Выберите другой лот — недельный лимит не потрачен.", now);
        }
    }

    // ── Shops ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getShopById(int shopId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM shops WHERE id = ?", shopId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> getShopOut(int shopId) {
        return jdbc.query("SELECT * FROM shops WHERE id = ?", SHOP_OUT, shopId)
            .stream().findFirst().orElse(null);
    }

    public Map<String, Object> updateShop(int shopId, String name, String contact, Double lat,
                                          Double lon, String city) {
        Map<String, Object> shop = getShopById(shopId);
        if (shop == null) {
            return null;
        }
        String newName = name != null ? name : (String) shop.get("name");
        String newContact = contact != null ? contact : (String) shop.get("contact");
        Double newLat = lat != null ? lat : numOrNull(shop.get("lat"));
        Double newLon = lon != null ? lon : numOrNull(shop.get("lon"));
        String newCity = city != null ? city : (String) shop.get("city");
        jdbc.update("UPDATE shops SET name = ?, contact = ?, lat = ?, lon = ?, city = ? WHERE id = ?",
            newName, newContact, newLat, newLon, newCity, shopId);
        return getShopOut(shopId);
    }

    // ── Notifications ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getNotifications(int shopId) {
        return jdbc.query(
            "SELECT * FROM notifications WHERE shop_id = ? ORDER BY created_at DESC",
            NOTIFICATION_OUT, shopId);
    }

    public Map<String, Object> getNotificationById(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM notifications WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markNotificationRead(int id) {
        jdbc.update("UPDATE notifications SET read = 1 WHERE id = ?", id);
    }

    // ── Receipts ────────────────────────────────────────────────────────────────

    /** Exact-duplicate check: same image bytes uploaded before (any shop). */
    public Map<String, Object> findReceiptBySha(String sha256) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, shop_id, status FROM receipts WHERE sha256 = ? LIMIT 1", sha256);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Near-duplicate check: same merchant+date+total on a non-rejected receipt. */
    public boolean fingerprintExists(String fp) {
        return !jdbc.queryForList(
            "SELECT 1 FROM receipts WHERE fingerprint = ? AND status != 'rejected' LIMIT 1", fp)
            .isEmpty();
    }

    public int createReceipt(int shopId, String photo, String sha256, String fp,
                             Map<String, Object> parsed, Map<String, Object> fraud, String status) {
        String itemsJson = toJson(parsed.get("items"));
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) fraud.get("reasons");
        String fraudReasons = reasons == null || reasons.isEmpty() ? null : String.join("; ", reasons);
        Integer id = jdbc.queryForObject(
            "INSERT INTO receipts (shop_id, photo, sha256, fingerprint, merchant, receipt_date, "
            + "total, currency, items, fraud_score, fraud_reasons, status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Integer.class,
            shopId, photo, sha256, fp, parsed.get("merchant"), parsed.get("receipt_date"),
            parsed.get("total"), parsed.get("currency"), itemsJson, fraud.get("score"),
            fraudReasons, status, OffsetDateTime.now());
        return id;
    }

    public Map<String, Object> getReceiptById(int receiptId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM receipts WHERE id = ?", receiptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void confirmReceipt(int receiptId, List<Integer> lotIds) {
        jdbc.update("UPDATE receipts SET status = 'confirmed', lot_ids = ?, confirmed_at = ? WHERE id = ?",
            toJson(lotIds), OffsetDateTime.now(), receiptId);
    }

    public List<Map<String, Object>> getReceipts(int shopId, int limit, int offset) {
        return jdbc.queryForList(
            "SELECT * FROM receipts WHERE shop_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            shopId, limit, offset);
    }

    // ── Row mappers (exact response_model shapes) ────────────────────────────────

    private static final RowMapper<Map<String, Object>> LOT_OUT = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("shop_id", rs.getInt("shop_id"));
        m.put("description", rs.getString("description"));
        m.put("quantity", getDouble(rs, "quantity"));
        m.put("initial_quantity", getDouble(rs, "initial_quantity"));
        m.put("unit", rs.getString("unit"));
        m.put("unit_weight_kg", getDouble(rs, "unit_weight_kg"));
        m.put("expiry_date", rs.getObject("expiry_date", LocalDate.class));
        m.put("photo", rs.getString("photo"));
        m.put("address", rs.getString("address"));
        m.put("time_slot", rs.getString("time_slot"));
        m.put("status", rs.getString("status"));
        m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
        m.put("taken_at", rs.getObject("taken_at", OffsetDateTime.class));
        m.put("taken_by", rs.getString("taken_by"));
        m.put("category", rs.getString("category"));
        m.put("comment", rs.getString("comment"));
        m.put("requires_cold", rs.getBoolean("requires_cold"));
        // Joined shop columns are absent on shop-scoped queries — null, as in LotOut.
        m.put("shop_name", null);
        m.put("shop_lat", null);
        m.put("shop_lon", null);
        m.put("shop_kind", null);
        return m;
    };

    /** Same as {@link #LOT_OUT} but carrying the joined shop columns (public /lots map). */
    private static final RowMapper<Map<String, Object>> LOT_OUT_WITH_SHOP = (rs, n) -> {
        Map<String, Object> m = LOT_OUT.mapRow(rs, n);
        m.put("shop_name", rs.getString("shop_name"));
        m.put("shop_lat", getDouble(rs, "shop_lat"));
        m.put("shop_lon", getDouble(rs, "shop_lon"));
        m.put("shop_kind", rs.getString("shop_kind"));
        return m;
    };

    private static final RowMapper<Map<String, Object>> SHOP_OUT = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("name", rs.getString("name"));
        m.put("contact", rs.getString("contact"));
        m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
        m.put("lat", getDouble(rs, "lat"));
        m.put("lon", getDouble(rs, "lon"));
        m.put("city", rs.getString("city"));
        m.put("kind", rs.getString("kind"));
        return m;
    };

    private static final RowMapper<Map<String, Object>> NOTIFICATION_OUT = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("shop_id", getInteger(rs, "shop_id"));
        m.put("lot_id", getInteger(rs, "lot_id"));
        m.put("type", rs.getString("type"));
        m.put("payload", rs.getString("payload"));
        m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
        m.put("read", rs.getInt("read"));
        return m;
    };

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private static Double getDouble(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v instanceof Number num ? num.doubleValue() : null;
    }

    private static Integer getInteger(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v instanceof Number num ? num.intValue() : null;
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Double numOrNull(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
