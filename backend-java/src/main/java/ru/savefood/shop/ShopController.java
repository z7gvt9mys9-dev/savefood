package ru.savefood.shop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.forecast.ForecastService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.Auth;
import ru.savefood.security.Authz;
import ru.savefood.security.CurrentUser;
import ru.savefood.shop.dto.LotCreate;
import ru.savefood.shop.dto.LotUpdate;
import ru.savefood.shop.dto.ReceiptConfirm;
import ru.savefood.shop.dto.ReceiptLotDraft;
import ru.savefood.shop.dto.SelfPickupConfirm;
import ru.savefood.shop.dto.ShopCreate;
import ru.savefood.shop.dto.ShopUpdate;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Java port of backend/shop/routes.py — the shop/donor surface (lots, receipts,
 * notifications, billing, ESG, self-pickup). Authenticated routes take an
 * {@code @Auth CurrentUser} (the analogue of {@code Depends(get_current_user)})
 * and call {@link Authz#ensureOwnerOrAdmin} for per-shop ownership;
 * {@code POST /shops/register} is public and rate-limited.
 */
@RestController
public class ShopController {

    private static final Pattern SF_CODE =
        Pattern.compile("SF-(\\d+)(?:-([A-Za-z0-9_-]+))?", Pattern.CASE_INSENSITIVE);

    private final ShopRepository repo;
    private final ShopService service;
    private final BillingService billing;
    private final ReceiptService receiptService;
    private final ForecastService forecast;
    private final EsgService esg;
    private final WebhookService webhooks;
    private final NeedsMatchService needsMatch;
    private final ru.savefood.upload.UploadService uploads;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String uploadDir;
    private final String receiptDir;

    public ShopController(ShopRepository repo, ShopService service, BillingService billing,
                          ReceiptService receiptService, ForecastService forecast, EsgService esg,
                          WebhookService webhooks, NeedsMatchService needsMatch,
                          ru.savefood.upload.UploadService uploads, RateLimiter rateLimiter,
                          @Value("${savefood.shop-upload-dir}") String uploadDir,
                          @Value("${savefood.receipt-upload-dir}") String receiptDir) {
        this.repo = repo;
        this.service = service;
        this.billing = billing;
        this.receiptService = receiptService;
        this.forecast = forecast;
        this.esg = esg;
        this.webhooks = webhooks;
        this.needsMatch = needsMatch;
        this.uploads = uploads;
        this.rateLimiter = rateLimiter;
        this.uploadDir = uploadDir;
        this.receiptDir = receiptDir;
    }

    // ── Registration ────────────────────────────────────────────────────────────

    @PostMapping("/shops/register")
    public Map<String, Object> registerShop(@RequestBody ShopCreate payload, HttpServletRequest request) {
        rateLimiter.check("shops:register", request.getRemoteAddr(), 5);
        if (isBlank(payload.username()) || isBlank(payload.password())) {
            throw new ApiException(400, "Укажите логин и пароль");
        }
        String kind = payload.kind() == null ? "business" : payload.kind();
        if (!kind.equals("business") && !kind.equals("private")) {
            throw new ApiException(400, "kind должен быть 'business' или 'private'");
        }
        int shopId = service.registerShop(payload.name(), payload.contact(), payload.lat(),
            payload.lon(), payload.city(), kind, payload.username(), payload.password());
        return Map.of("id", shopId);
    }

    // ── Lots ──────────────────────────────────────────────────────────────────

    @PostMapping("/shops/{shopId}/lots")
    public Map<String, Object> createLot(@PathVariable int shopId, @RequestBody LotCreate payload,
                                         @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        Map<String, Object> shop = requireShop(shopId);

        String unit = payload.unit() == null ? "кг" : payload.unit();
        double weight = resolveWeight(unit, payload.unitWeightKg());
        // C2C anti-abuse (§45): a private donor's lot must show the actual food.
        if ("private".equals(shop.get("kind")) && isBlank(payload.photo())) {
            throw new ApiException(400, "Для частных доноров фотография лота обязательна");
        }
        if (payload.quantity() == null) {
            throw new ApiException(422, "quantity обязателен");
        }
        int lotId = service.createLot(shopId, payload.description(), payload.quantity(),
            payload.expiryDate(), payload.photo(), payload.address(), payload.timeSlot(),
            payload.category(), payload.comment(),
            Boolean.TRUE.equals(payload.requiresCold()), unit, weight);
        needsMatch.startNeedsMatch(lotId);
        return Map.of("id", lotId);
    }

    /** Photos per lot: the multi-upload form caps at this many files. */
    private static final int MAX_LOT_PHOTOS = 5;

    @PostMapping(value = "/shops/{shopId}/lots/upload",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> createLotUpload(
            @PathVariable int shopId,
            @RequestParam String description,
            @RequestParam double quantity,
            @RequestParam(defaultValue = "кг") String unit,
            @RequestParam(name = "unit_weight_kg", defaultValue = "1.0") double unitWeightKg,
            @RequestParam(name = "expiry_date", required = false) String expiryDate,
            @RequestParam(required = false) String address,
            @RequestParam(name = "time_slot", required = false) String timeSlot,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String comment,
            @RequestParam(name = "requires_cold", defaultValue = "false") boolean requiresCold,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) List<MultipartFile> files,
            @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        Map<String, Object> shop = requireShop(shopId);

        double weight = resolveWeight(unit, unitWeightKg);
        // `files` — новая мультизагрузка; одиночный `file` остаётся как fallback
        // для старых клиентов (и дублирует первый элемент files).
        List<MultipartFile> incoming = new java.util.ArrayList<>();
        if (files != null) {
            files.stream().filter(ShopController::hasContent).forEach(incoming::add);
        }
        if (incoming.isEmpty() && hasContent(file)) {
            incoming.add(file);
        }
        if (incoming.size() > MAX_LOT_PHOTOS) {
            throw new ApiException(400, "Не более " + MAX_LOT_PHOTOS + " фотографий на лот");
        }
        if ("private".equals(shop.get("kind")) && incoming.isEmpty()) {
            throw new ApiException(400, "Для частных доноров фотография лота обязательна");
        }
        List<String> photoUrls = new java.util.ArrayList<>();
        for (MultipartFile f : incoming) {
            photoUrls.add("/uploads/" + uploads.validateAndSave(f, uploadDir));
        }
        int lotId = service.createLotWithPhotos(shopId, description, quantity, parseDate(expiryDate),
            photoUrls, address, timeSlot, category, comment, requiresCold, unit, weight);
        needsMatch.startNeedsMatch(lotId);
        return Map.of("id", lotId);
    }

    private static boolean hasContent(MultipartFile f) {
        return f != null && f.getOriginalFilename() != null && !f.getOriginalFilename().isEmpty();
    }

    @GetMapping("/shops/{shopId}/lots")
    public List<Map<String, Object>> listActiveLots(@PathVariable int shopId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        return repo.getActiveLots(shopId);
    }

    @DeleteMapping("/lots/{lotId}")
    public Map<String, Object> deleteLot(@PathVariable int lotId, @Auth CurrentUser user) {
        requireLotOwner(lotId, user);
        if (!service.deleteLot(lotId)) {
            throw new ApiException(404, "Lot not found or cannot be deleted");
        }
        return Map.of("ok", true);
    }

    @PatchMapping("/lots/{lotId}")
    public Map<String, Object> patchLot(@PathVariable int lotId, @RequestBody LotUpdate payload,
                                        @Auth CurrentUser user) {
        Map<String, Object> lot = requireLotOwner(lotId, user);
        String newUnit = payload.unit() != null ? payload.unit() : (String) lot.getOrDefault("unit", "кг");
        Double newWeight = payload.unitWeightKg() != null ? payload.unitWeightKg()
            : asDouble(lot.getOrDefault("unit_weight_kg", 1.0));
        if (!"кг".equals(newUnit)) {
            if (newWeight == null || newWeight <= 0) {
                throw new ApiException(400,
                    "Для единиц измерения кроме 'кг' необходимо указать вес одной единицы (кг)");
            }
        } else {
            newWeight = 1.0;
        }
        Map<String, Object> updated = repo.updateLot(lotId, payload.description(), payload.quantity(),
            payload.expiryDate(), payload.address(), payload.category(), payload.comment(),
            payload.requiresCold(), payload.unit(), newWeight);
        if (updated == null) {
            throw new ApiException(404, "Lot not found or cannot be updated");
        }
        return updated;
    }

    @PostMapping("/lots/{lotId}/confirm_transfer")
    public Map<String, Object> confirmTransfer(@PathVariable int lotId, @Auth CurrentUser user) {
        Map<String, Object> lot = requireLotOwner(lotId, user);
        if (!repo.confirmLotTransfer(lotId)) {
            throw new ApiException(400, "Lot not found or not in taken status");
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lot_id", lotId);
            data.put("description", lot.get("description"));
            data.put("quantity", lot.get("quantity"));
            webhooks.fire(((Number) lot.get("shop_id")).intValue(), "lot.confirmed", data);
        } catch (RuntimeException ignored) {
            // best-effort, like the Python try/except around webhook_service.fire
        }
        return Map.of("ok", true);
    }

    @GetMapping("/shops/{shopId}/history")
    public List<Map<String, Object>> getHistory(@PathVariable int shopId,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        return repo.getHistory(shopId, limit, offset);
    }

    // ── Shop profile ────────────────────────────────────────────────────────────

    @GetMapping("/shops/{shopId}")
    public Map<String, Object> getShop(@PathVariable int shopId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        Map<String, Object> shop = repo.getShopOut(shopId);
        if (shop == null) {
            throw new ApiException(404, "Shop not found");
        }
        return shop;
    }

    @PatchMapping("/shops/{shopId}")
    public Map<String, Object> patchShop(@PathVariable int shopId, @RequestBody ShopUpdate payload,
                                         @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        Map<String, Object> updated = repo.updateShop(shopId, payload.name(), payload.contact(),
            payload.lat(), payload.lon(), payload.city());
        if (updated == null) {
            throw new ApiException(404, "Shop not found or cannot be updated");
        }
        return updated;
    }

    // ── Notifications ───────────────────────────────────────────────────────────

    @GetMapping("/shops/{shopId}/notifications")
    public List<Map<String, Object>> notifications(@PathVariable int shopId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        return repo.getNotifications(shopId);
    }

    @PatchMapping("/shops/notifications/{notificationId}/read")
    public Map<String, Object> markNotificationRead(@PathVariable int notificationId,
                                                    @Auth CurrentUser user) {
        Map<String, Object> note = repo.getNotificationById(notificationId);
        if (note == null) {
            throw new ApiException(404, "Notification not found");
        }
        Object shopId = note.get("shop_id");
        Authz.ensureOwnerOrAdmin(user, "shop", shopId == null ? -1 : ((Number) shopId).intValue());
        repo.markNotificationRead(notificationId);
        return Map.of("ok", true);
    }

    // ── Receipts (OCR) ──────────────────────────────────────────────────────────

    @PostMapping(value = "/shops/{shopId}/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadReceipt(@PathVariable int shopId,
                                             @RequestParam MultipartFile file,
                                             @Auth CurrentUser user, HttpServletRequest request) {
        rateLimiter.check("shops:receipts", request.getRemoteAddr(), 10);
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        billing.requireFeature(shopId, "ocr");

        String filename = uploads.validateAndSave(file, receiptDir);
        Path path = Paths.get(receiptDir, filename);
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ApiException(500, "Could not read uploaded receipt");
        }

        // Cheapest check first: byte-identical photo already uploaded (any shop).
        String sha = receiptService.sha256Hex(content);
        if (repo.findReceiptBySha(sha) != null) {
            deleteQuietly(path);
            throw new ApiException(409, "Этот чек уже загружался (идентичное фото)");
        }

        Map<String, Object> parsed = receiptService.parseReceiptImage(content, file.getContentType());
        if (parsed == null) {
            deleteQuietly(path);
            throw new ApiException(503,
                "Распознавание временно недоступно, попробуйте позже или создайте лот вручную");
        }
        if (!Boolean.TRUE.equals(parsed.get("is_receipt"))) {
            deleteQuietly(path);
            throw new ApiException(400, "На фото не распознан кассовый чек");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) parsed.get("items");
        if (items == null || items.isEmpty()) {
            deleteQuietly(path);
            throw new ApiException(400, "В чеке не найдено продуктовых позиций");
        }

        String fp = receiptService.fingerprint(parsed);
        boolean fpDupe = fp != null && repo.fingerprintExists(fp);
        Map<String, Object> fraud = receiptService.evaluateFraud(parsed, fpDupe);
        String status = Boolean.TRUE.equals(fraud.get("rejected")) ? "rejected" : "parsed";
        int receiptId = repo.createReceipt(shopId, "/receipts/" + filename, sha, fp, parsed, fraud, status);

        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("receipt_id", receiptId);
            data.put("status", status);
            data.put("fraud_score", fraud.get("score"));
            data.put("items_count", items.size());
            data.put("total", parsed.get("total"));
            webhooks.fire(shopId, "receipt.parsed", data);
        } catch (RuntimeException ignored) {
            // best-effort, like the Python try/except around webhook_service.fire
        }
        return receiptToOut(repo.getReceiptById(receiptId));
    }

    @PostMapping("/shops/{shopId}/receipts/{receiptId}/confirm")
    public Map<String, Object> confirmReceipt(@PathVariable int shopId, @PathVariable int receiptId,
                                              @RequestBody ReceiptConfirm payload, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        // Re-gate here: the plan may have been downgraded between upload and confirm.
        billing.requireFeature(shopId, "ocr");
        Map<String, Object> receipt = repo.getReceiptById(receiptId);
        if (receipt == null || ((Number) receipt.get("shop_id")).intValue() != shopId) {
            throw new ApiException(404, "Чек не найден");
        }
        String status = (String) receipt.get("status");
        if ("rejected".equals(status)) {
            Object reasons = receipt.get("fraud_reasons");
            throw new ApiException(400, "Чек отклонён антифродом: " + (reasons == null ? "" : reasons));
        }
        if ("confirmed".equals(status)) {
            throw new ApiException(400, "Лоты по этому чеку уже созданы");
        }
        if (payload.lots() == null || payload.lots().isEmpty()) {
            throw new ApiException(422, "lots: список не может быть пустым");
        }

        Map<String, Object> shop = repo.getShopById(shopId);
        String address = payload.address() != null ? payload.address()
            : (shop == null ? null : (String) shop.get("city"));
        // Validate categories before the quota lock so a bad payload doesn't hold it.
        for (ReceiptLotDraft draft : payload.lots()) {
            if (!ReceiptService.LOT_CATEGORIES.contains(draft.category())) {
                throw new ApiException(400, "Неизвестная категория: " + draft.category());
            }
        }
        List<Integer> lotIds = service.confirmReceiptLots(shopId, receiptId, payload.lots(),
            payload.expiryDate(), address, payload.timeSlot());
        for (int lid : lotIds) {
            needsMatch.startNeedsMatch(lid);
        }
        return Map.of("ok", true, "lot_ids", lotIds);
    }

    @GetMapping("/shops/{shopId}/receipts")
    public List<Map<String, Object>> listReceipts(@PathVariable int shopId,
                                                  @RequestParam(defaultValue = "20") int limit,
                                                  @RequestParam(defaultValue = "0") int offset,
                                                  @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : repo.getReceipts(shopId, limit, offset)) {
            out.add(receiptToOut(r));
        }
        return out;
    }

    @GetMapping("/shops/{shopId}/receipts/{receiptId}/image")
    public ResponseEntity<Resource> getReceiptImage(@PathVariable int shopId,
                                                    @PathVariable int receiptId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        Map<String, Object> receipt = repo.getReceiptById(receiptId);
        String photo = receipt == null ? null : (String) receipt.get("photo");
        if (receipt == null || ((Number) receipt.get("shop_id")).intValue() != shopId
                || photo == null || photo.isEmpty()) {
            throw new ApiException(404, "Чек не найден");
        }
        Path path = Paths.get(receiptDir, Paths.get(photo).getFileName().toString());
        if (!Files.isRegularFile(path)) {
            throw new ApiException(404, "Файл не найден");
        }
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) {
                type = MediaType.parseMediaType(probed);
            }
        } catch (IOException ignored) {
            // fall back to octet-stream
        }
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(path));
    }

    // ── Forecast / billing / ESG ──────────────────────────────────────────────────

    @GetMapping("/shops/{shopId}/forecast")
    public Map<String, Object> getForecast(@PathVariable int shopId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        return forecast.shopForecast(shopId);
    }

    @GetMapping("/shops/{shopId}/plan")
    public Map<String, Object> getPlan(@PathVariable int shopId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        return billing.planSummary(shopId);
    }

    @GetMapping("/shops/{shopId}/esg")
    public Map<String, Object> getEsgReport(@PathVariable int shopId,
                                            @RequestParam(defaultValue = "12") int months,
                                            @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        requireShop(shopId);
        billing.requireFeature(shopId, "esg");
        return esg.shopReport(shopId, months);
    }

    @GetMapping("/shops/{shopId}/esg/report.csv")
    public ResponseEntity<String> getEsgReportCsv(@PathVariable int shopId,
                                                  @RequestParam(defaultValue = "12") int months,
                                                  @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        Map<String, Object> shop = requireShop(shopId);
        billing.requireFeature(shopId, "esg");
        Map<String, Object> report = esg.shopReport(shopId, months);
        String shopName = shop.get("name") != null ? (String) shop.get("name") : "shop-" + shopId;
        String csv = esg.reportToCsv(report, shopName);
        String filename = "savefood_donations_shop" + shopId + "_" + months + "m.csv";
        // UTF-8 BOM so Excel opens Cyrillic correctly.
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body("﻿" + csv);
    }

    // ── Self-pickup ─────────────────────────────────────────────────────────────

    @PostMapping("/shops/{shopId}/self_pickup/confirm")
    public Map<String, Object> confirmSelfPickup(@PathVariable int shopId,
                                                 @RequestBody SelfPickupConfirm payload,
                                                 @Auth CurrentUser user, HttpServletRequest request) {
        rateLimiter.check("shops:self_pickup", request.getRemoteAddr(), 20);
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        String code = payload.code() == null ? "" : payload.code().strip();
        Matcher m = SF_CODE.matcher(code);
        if (!m.matches()) {
            throw new ApiException(400, "Неверный формат кода (ожидается SF-<номер>-<код>)");
        }
        int ticketId = Integer.parseInt(m.group(1));
        String providedSecret = m.group(2);
        service.confirmSelfPickup(shopId, ticketId, providedSecret);
        return Map.of("ok", true, "ticket_id", ticketId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Map<String, Object> requireShop(int shopId) {
        Map<String, Object> shop = repo.getShopById(shopId);
        if (shop == null) {
            throw new ApiException(404, "Shop not found");
        }
        return shop;
    }

    /** Return the lot if the caller owns its shop (or is admin), else 404/403. */
    private Map<String, Object> requireLotOwner(int lotId, CurrentUser user) {
        Map<String, Object> lot = repo.getLotById(lotId);
        if (lot == null) {
            throw new ApiException(404, "Lot not found");
        }
        Authz.ensureOwnerOrAdmin(user, "shop", ((Number) lot.get("shop_id")).intValue());
        return lot;
    }

    /** DB receipt row → ReceiptOut payload (items JSON unpacked, drafts regrouped). */
    private Map<String, Object> receiptToOut(Map<String, Object> row) {
        List<Map<String, Object>> items = readJsonList(row.get("items"));
        double score = asPrimitiveDouble(row.get("fraud_score"));
        String status = (String) row.get("status");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ((Number) row.get("id")).intValue());
        out.put("status", status);
        out.put("merchant", row.get("merchant"));
        out.put("receipt_date", toLocalDate(row.get("receipt_date")));
        out.put("total", asDouble(row.get("total")));
        out.put("currency", row.get("currency"));
        out.put("items", items);
        out.put("fraud_score", asDouble(row.get("fraud_score")));
        out.put("fraud_reasons", row.get("fraud_reasons"));
        out.put("fraud_flagged", score >= ReceiptService.FRAUD_FLAG_THRESHOLD);
        out.put("suggested_lots", "parsed".equals(status) ? receiptService.suggestLots(items) : List.of());
        out.put("lot_ids", readJsonIntList(row.get("lot_ids")));
        out.put("created_at", row.get("created_at"));
        return out;
    }

    private List<Map<String, Object>> readJsonList(Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            return mapper.readValue(json.toString(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Integer> readJsonIntList(Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            return mapper.readValue(json.toString(), new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double resolveWeight(String unit, Double weight) {
        if (!"кг".equals(unit)) {
            if (weight == null || weight <= 0) {
                throw new ApiException(400,
                    "Для единиц измерения кроме 'кг' необходимо указать вес одной единицы (кг)");
            }
            return weight;
        }
        return 1.0;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (RuntimeException e) {
            throw new ApiException(422, "Некорректная дата: " + s);
        }
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(v.toString().substring(0, 10));
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static double asPrimitiveDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort, like the Python os.remove in a failure branch
        }
    }
}
