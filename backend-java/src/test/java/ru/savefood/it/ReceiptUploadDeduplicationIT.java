package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.forecast.ForecastService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.CurrentUser;
import ru.savefood.shop.ShopController;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;

/** PostgreSQL coverage for exact receipt-hash winner election and cleanup. */
class ReceiptUploadDeduplicationIT extends PostgresIT {

    @TempDir
    Path receiptDir;

    private ExecutorService executor;
    private int shopId;
    private ShopRepository receipts;
    private BillingService billing;
    private ReceiptService receiptService;
    private WebhookService webhooks;
    private ShopController controller;

    @BeforeEach
    void wire() {
        executor = Executors.newFixedThreadPool(2);
        shopId = insertShop("Receipt shop", 43.238, 76.889);
        jdbc.update("UPDATE shops SET plan = 'pro' WHERE id = ?", shopId);
        receipts = new ShopRepository(jdbc);
        billing = new BillingService(jdbc);
        receiptService = spy(new ReceiptService("", "gemini-test", 48));
        webhooks = mock(WebhookService.class);
        doReturn(parsedReceipt()).when(receiptService).parseReceiptImage(any(byte[].class), any());
        controller = controller(receiptService);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void normalUniqueReceiptUploadSucceeds() throws Exception {
        Map<String, Object> response = upload(png(Color.GREEN), "10.0.0.1");

        assertThat(response.get("id")).isNotNull();
        assertThat(receiptCount()).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(1);
        verify(receiptService, times(1)).suggestLots(anyList());
        verify(webhooks, times(1)).fire(anyInt(), anyString(), any());
        assertThat(billing.lotsCreatedThisMonth(shopId)).isZero();
    }

    @Test
    void sequentialDuplicateAndRetryRemainConflictSafe() throws Exception {
        byte[] content = png(Color.BLUE);
        upload(content, "10.0.0.2");

        assertDuplicate(() -> upload(content, "10.0.0.2"));
        assertDuplicate(() -> upload(content, "10.0.0.2"));

        assertThat(receiptCount()).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(1);
        verify(receiptService, times(1)).parseReceiptImage(any(byte[].class), any());
        verify(receiptService, times(1)).suggestLots(anyList());
        verify(webhooks, times(1)).fire(anyInt(), anyString(), any());
        assertThat(billing.lotsCreatedThisMonth(shopId)).isZero();
    }

    @Test
    void concurrentIdenticalUploadsHaveOneWinnerAndCleanLoser() throws Exception {
        CountDownLatch bothInOcr = new CountDownLatch(2);
        CountDownLatch releaseOcr = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothInOcr.countDown();
            if (!releaseOcr.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent uploads did not both reach OCR");
            }
            return parsedReceipt();
        }).when(receiptService).parseReceiptImage(any(byte[].class), any());
        byte[] content = png(Color.RED);

        Future<UploadOutcome> first = executor.submit(() -> outcome(content, "10.0.0.3"));
        Future<UploadOutcome> second = executor.submit(() -> outcome(content, "10.0.0.4"));
        assertThat(bothInOcr.await(5, TimeUnit.SECONDS)).isTrue();
        releaseOcr.countDown();

        List<UploadOutcome> outcomes = List.of(first.get(5, TimeUnit.SECONDS),
            second.get(5, TimeUnit.SECONDS));
        assertThat(outcomes).filteredOn(UploadOutcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(result -> result.status() == 409).hasSize(1);
        assertThat(receiptCount()).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(1);
        verify(receiptService, times(2)).parseReceiptImage(any(byte[].class), any());
        verify(receiptService, times(1)).suggestLots(anyList());
        verify(webhooks, times(1)).fire(anyInt(), anyString(), any());
        assertThat(billing.lotsCreatedThisMonth(shopId)).isZero();
    }

    @Test
    void differentHashesCanUploadConcurrently() throws Exception {
        CountDownLatch bothInOcr = new CountDownLatch(2);
        CountDownLatch releaseOcr = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothInOcr.countDown();
            if (!releaseOcr.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent uploads did not both reach OCR");
            }
            return parsedReceipt();
        }).when(receiptService).parseReceiptImage(any(byte[].class), any());

        Future<UploadOutcome> first = executor.submit(() -> outcome(png(Color.BLACK), "10.0.0.5"));
        Future<UploadOutcome> second = executor.submit(() -> outcome(png(Color.WHITE), "10.0.0.6"));
        assertThat(bothInOcr.await(5, TimeUnit.SECONDS)).isTrue();
        releaseOcr.countDown();

        assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
            .allMatch(UploadOutcome::success);
        assertThat(receiptCount()).isEqualTo(2);
        assertThat(fileCount()).isEqualTo(2);
    }

    @Test
    void migrationRetainsLegacyDuplicateRowsAndMarksTheirCanonicalReceipt() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target("8").load().migrate();
        int legacyShop = insertShop("Legacy receipt shop", 43.238, 76.889);
        int canonical = insertLegacyReceipt(legacyShop, "same-hash", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
        int duplicate = insertLegacyReceipt(legacyShop, "same-hash", OffsetDateTime.parse("2025-01-02T00:00:00Z"));

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM receipts WHERE id = ?", String.class, canonical))
            .isEqualTo("same-hash");
        assertThat(jdbc.queryForObject(
            "SELECT duplicate_of_receipt_id FROM receipts WHERE id = ?", Integer.class, duplicate))
            .isEqualTo(canonical);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM receipts WHERE id = ?", String.class, duplicate))
            .isNull();
        assertThatThrownBy(() -> insertLegacyReceipt(legacyShop, "same-hash", OffsetDateTime.now()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ShopController controller(ReceiptService ocr) {
        return new ShopController(receipts, mock(ShopService.class), billing, ocr,
            mock(ForecastService.class), mock(EsgService.class), webhooks,
            mock(NeedsMatchService.class), new UploadService(), new RateLimiter(),
            mock(ru.savefood.shop.LotPhotoReferenceService.class),
            receiptDir.toString(), receiptDir.toString());
    }

    private Map<String, Object> upload(byte[] content, String clientIp) {
        MockMultipartFile file = new MockMultipartFile(
            "file", "receipt.png", "image/png", content);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(clientIp);
        return controller.uploadReceipt(shopId, file,
            new CurrentUser(1, "receipt-shop", "shop", shopId), request);
    }

    private UploadOutcome outcome(byte[] content, String clientIp) {
        try {
            upload(content, clientIp);
            return new UploadOutcome(true, 200);
        } catch (ApiException e) {
            return new UploadOutcome(false, e.getStatus());
        }
    }

    private void assertDuplicate(ThrowingUpload action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(409));
    }

    private int receiptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class);
    }

    private long fileCount() throws Exception {
        try (var files = Files.list(receiptDir)) {
            return files.count();
        }
    }

    private int insertLegacyReceipt(int owner, String sha, OffsetDateTime createdAt) {
        return jdbc.queryForObject(
            "INSERT INTO receipts (shop_id, photo, sha256, items, status, created_at) "
            + "VALUES (?, '/receipts/legacy.png', ?, '[]', 'parsed', ?) RETURNING id",
            Integer.class, owner, sha, createdAt);
    }

    private static Map<String, Object> parsedReceipt() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Bread");
        item.put("category", "Выпечка");
        item.put("weight_kg", 1.0);
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("is_receipt", true);
        parsed.put("merchant", "Test shop");
        parsed.put("receipt_date", null);
        parsed.put("total", null);
        parsed.put("currency", "RUB");
        parsed.put("items", List.of(item));
        parsed.put("authenticity", "ok");
        parsed.put("authenticity_reason", null);
        return parsed;
    }

    private static byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        image.setRGB(1, 1, color.getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private record UploadOutcome(boolean success, int status) {
    }

    @FunctionalInterface
    private interface ThrowingUpload {
        void run() throws Exception;
    }
}
