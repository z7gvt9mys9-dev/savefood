package ru.savefood.shop;
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
@Repository
public class ShopRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    public ShopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    /** Raw lot row (all columns) for internal ownership/transition checks. */
    public Map<String, Object> getLotById(int lotId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM lots WHERE id = ?", lotId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public int createLot(int shopId, String description, double quantity, LocalDate expiryDate,
                         String photo, String address, String timeSlot, String category,
                         String comment, boolean requiresCold, String unit, double unitWeightKg) {
        return createLotMultiPhoto(shopId, description, quantity, expiryDate,
            photo == null || photo.isBlank() ? List.of() : List.of(photo),
            address, timeSlot, category, comment, requiresCold, unit, unitWeightKg);
    }
    private static final int LOT_PHOTO_STAGING_LOCK = 1_397_116_752;
    public record PendingLotPhotoUsage(long count, long bytes) {
    }
    /** Serializes quota decisions for one shop until the surrounding transaction ends. */
    public void lockLotPhotoStaging(int shopId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null,
            LOT_PHOTO_STAGING_LOCK, shopId);
    }
    public PendingLotPhotoUsage pendingLotPhotoUsage(int shopId) {
        return jdbc.queryForObject("SELECT COUNT(*), COALESCE(SUM(byte_size), 0) "
                + "FROM shop_lot_photo_uploads WHERE shop_id = ? AND lot_id IS NULL",
            (rs, rowNum) -> new PendingLotPhotoUsage(rs.getLong(1), rs.getLong(2)), shopId);
    }
    /** Records a validated image produced by the shop lot-photo upload endpoint. */
    public void stageLotPhotoUpload(int shopId, String filename, long byteSize, long ttlMillis) {
        jdbc.update("INSERT INTO shop_lot_photo_uploads "
                + "(filename, shop_id, byte_size, expires_at) "
                + "VALUES (?, ?, ?, clock_timestamp() + (? * INTERVAL '1 millisecond'))",
            filename, shopId, byteSize, ttlMillis);
    }
    /** Fast-fail check; {@link #claimLotPhotoUpload} remains authoritative. */
    public boolean hasAvailableLotPhotoUpload(int shopId, String filename) {
        return Boolean.TRUE.equals(jdbc.query(
            "SELECT EXISTS (SELECT 1 FROM shop_lot_photo_uploads "
                + "WHERE shop_id = ? AND filename = ? AND lot_id IS NULL "
                + "AND expires_at > clock_timestamp())",
            rs -> rs.next() && rs.getBoolean(1), shopId, filename));
    }
    /** Atomically binds a staged upload to exactly one lot owned by its uploader. */
    public boolean claimLotPhotoUpload(int shopId, String filename, int lotId) {
        return jdbc.update("UPDATE shop_lot_photo_uploads SET lot_id = ?, claimed_at = CURRENT_TIMESTAMP "
                + "WHERE shop_id = ? AND filename = ? AND lot_id IS NULL "
                + "AND expires_at > clock_timestamp()",
            lotId, shopId, filename) == 1;
    }
    public int createLotMultiPhoto(int shopId, String description, double quantity, LocalDate expiryDate,
                                   List<String> photos, String address, String timeSlot, String category,
                                   String comment, boolean requiresCold, String unit, double unitWeightKg) {
        String photo = photos == null || photos.isEmpty() ? null : photos.get(0);
        String photosJson = photos == null || photos.isEmpty() ? null : toJson(photos);
        String city = jdbc.query("SELECT city FROM shops WHERE id = ?",
            (rs, n) -> rs.getString("city"), shopId).stream().findFirst().orElse(null);
        Integer id = jdbc.queryForObject(
            "INSERT INTO lots (shop_id, description, quantity, initial_quantity, unit, unit_weight_kg, "
            + "expiry_date, photo, photos, address, time_slot, category, comment, city, requires_cold, status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?) RETURNING id",
            Integer.class,
            shopId, description, quantity, quantity, unit, unitWeightKg,
            expiryDate, photo, photosJson, address, timeSlot, category, comment, city, requiresCold,
            OffsetDateTime.now());
        return id;
    }
    public List<Map<String, Object>> getAllActiveLots(int limit, int offset, String category, String search) {
        StringBuilder sql = new StringBuilder(
            "SELECT l.*, s.name AS shop_name, s.lat AS shop_lat, s.lon AS shop_lon, s.kind AS shop_kind "
            + "FROM lots l JOIN shops s ON s.id = l.shop_id "
            + "WHERE l.status = 'active' AND l.quantity >= 1 "
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
    public List<Map<String, Object>> getActiveLots(int shopId) {
        return jdbc.query(
            "SELECT * FROM lots WHERE shop_id = ? AND ("
            + "(status = 'active' AND quantity >= 1 AND "
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
    public Map<String, Object> updateLot(int lotId, String description, Double quantity,
                                         LocalDate expiryDate, String address, String category,
                                         String comment, Boolean requiresCold, String unit,
                                         Double unitWeightKg, Double expectedQuantity,
                                         Double expectedInitialQuantity) {
        boolean changesQuantity = quantity != null;
        List<Map<String, Object>> rows = jdbc.query(
            "UPDATE lots SET "
            + "description = COALESCE(?, description), "
            + "quantity = CASE WHEN ? THEN quantity + (? - ?) "
            + "ELSE quantity END, "
            + "initial_quantity = CASE WHEN ? THEN COALESCE(initial_quantity, ?) + (? - ?) "
            + "ELSE initial_quantity END, "
            + "expiry_date = COALESCE(?, expiry_date), address = COALESCE(?, address), "
            + "category = COALESCE(?, category), comment = COALESCE(?, comment), "
            + "requires_cold = COALESCE(?, requires_cold), unit = COALESCE(?, unit), "
            + "unit_weight_kg = COALESCE(?, unit_weight_kg) "
            + "WHERE id = ? AND status = 'active' "
            + "AND (NOT ? OR initial_quantity IS NOT DISTINCT FROM ?) "
            + "AND (NOT ? OR quantity + (? - ?) >= 0) "
            + "AND (NOT ? OR COALESCE(initial_quantity, ?) + (? - ?) >= 0) "
            + "RETURNING *",
            LOT_OUT,
            description, changesQuantity, quantity, expectedQuantity,
            changesQuantity, expectedQuantity, quantity, expectedQuantity,
            expiryDate, address, category, comment, requiresCold, unit, unitWeightKg,
            lotId,
            changesQuantity, expectedInitialQuantity,
            changesQuantity, quantity, expectedQuantity,
            changesQuantity, expectedQuantity, quantity, expectedQuantity);
        return rows.stream().findFirst().orElse(null);
    }
    public boolean confirmLotTransfer(int lotId) {
        return jdbc.update(
            "UPDATE lots SET status = 'confirmed' WHERE id = ? AND status = 'taken'", lotId) == 1;
    }
    public boolean deleteLot(int lotId) {
        List<Integer> shopIds = jdbc.query(
            "UPDATE lots SET status = 'removed' WHERE id = ? AND status = 'active' RETURNING shop_id",
            (rs, n) -> rs.getInt("shop_id"), lotId);
        if (shopIds.isEmpty()) {
            return false;
        }
        cancelOpenTickets(lotId, "лот удалён магазином");
        try {
            jdbc.update(
                "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, ?, 0)",
                shopIds.get(0), lotId, "lot_removed", "Лот #" + lotId + " удалён магазином",
                OffsetDateTime.now());
        } catch (RuntimeException ignored) {
        }
        return true;
    }
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
    public Integer createReceipt(int shopId, String photo, String sha256, String fp,
                                 Map<String, Object> parsed, Map<String, Object> fraud, String status) {
        String itemsJson = toJson(parsed.get("items"));
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) fraud.get("reasons");
        String fraudReasons = reasons == null || reasons.isEmpty() ? null : String.join("; ", reasons);
        List<Integer> ids = jdbc.query(
            "INSERT INTO receipts (shop_id, photo, sha256, fingerprint, merchant, receipt_date, "
            + "total, currency, items, fraud_score, fraud_reasons, status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT ON CONSTRAINT uq_receipts_sha256_exact DO NOTHING RETURNING id",
            (rs, rowNum) -> rs.getInt("id"),
            shopId, photo, sha256, fp, parsed.get("merchant"), parsed.get("receipt_date"),
            parsed.get("total"), parsed.get("currency"), itemsJson, fraud.get("score"),
            fraudReasons, status, OffsetDateTime.now());
        return ids.isEmpty() ? null : ids.get(0);
    }
    public Map<String, Object> getReceiptById(int receiptId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM receipts WHERE id = ?", receiptId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** Locks the source receipt for an atomic status check + lot creation transaction. */
    public Map<String, Object> getReceiptForUpdate(int receiptId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM receipts WHERE id = ? FOR UPDATE", receiptId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** Returns false if another confirmation changed the receipt while we waited. */
    public boolean confirmReceipt(int receiptId, List<Integer> lotIds) {
        return jdbc.update(
            "UPDATE receipts SET status = 'confirmed', lot_ids = ?, confirmed_at = ? "
            + "WHERE id = ? AND status = 'parsed'",
            toJson(lotIds), OffsetDateTime.now(), receiptId) == 1;
    }
    public List<Map<String, Object>> getReceipts(int shopId, int limit, int offset) {
        return jdbc.queryForList(
            "SELECT * FROM receipts WHERE shop_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            shopId, limit, offset);
    }
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
        m.put("photos", parsePhotos(rs));
        m.put("address", rs.getString("address"));
        m.put("time_slot", rs.getString("time_slot"));
        m.put("status", rs.getString("status"));
        m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
        m.put("taken_at", rs.getObject("taken_at", OffsetDateTime.class));
        m.put("taken_by", rs.getString("taken_by"));
        m.put("category", rs.getString("category"));
        m.put("comment", rs.getString("comment"));
        m.put("requires_cold", rs.getBoolean("requires_cold"));
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
    private static final ObjectMapper PHOTOS_MAPPER = new ObjectMapper();
    private static List<String> parsePhotos(ResultSet rs) {
        try {
            String raw = rs.getString("photos");
            if (raw != null && !raw.isBlank()) {
                return PHOTOS_MAPPER.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            }
        } catch (Exception ignore) {
        }
        try {
            String single = rs.getString("photo");
            return single == null || single.isBlank() ? List.of() : List.of(single);
        } catch (SQLException e) {
            return List.of();
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
