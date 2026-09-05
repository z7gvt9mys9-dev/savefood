package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
import java.util.Properties;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.springframework.transaction.support.DefaultTransactionStatus;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.forecast.ForecastService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.CurrentUser;
import ru.savefood.shop.ShopController;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.storage.SensitiveFileCleanup;
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
    private SensitiveFileCleanup cleanup;
    private ShopController controller;
    @BeforeEach
    void wire() {
        executor = Executors.newFixedThreadPool(2);
        shopId = insertShop("Receipt shop", 43.238, 76.889);
        jdbc.update("UPDATE shops SET plan = 'pro' WHERE id = ?", shopId);
        receipts = new ShopRepository(jdbc);
        billing = new BillingService(jdbc);
        receiptService = spy(new ReceiptService("", "gemini-test", 48,
            java.time.Clock.fixed(java.time.Instant.parse("2026-01-02T12:00:00Z"),
                java.time.ZoneId.of("Europe/Moscow"))));
        webhooks = mock(WebhookService.class);
        cleanup = new SensitiveFileCleanup(jdbc, receiptDir.toString(), receiptDir.toString(),
            receiptDir.toString(), receiptDir.toString(), receiptDir.toString());
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
    void readFailureAfterSaveRemovesTheUncommittedReceiptFile() throws Exception {
        UploadService deletingAfterSave = spy(new UploadService());
        doAnswer(invocation -> {
            String filename = (String) invocation.callRealMethod();
            Files.delete(receiptDir.resolve(filename));
            return filename;
        }).when(deletingAfterSave).validateAndSave(any(), anyString());
        controller = controller(receiptService, receipts, deletingAfterSave);
        assertThatThrownBy(() -> upload(png(Color.MAGENTA), "10.0.0.7"))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(500));
        assertThat(receiptCount()).isZero();
        assertThat(fileCount()).isZero();
    }
    @Test
    void databaseFailureAfterSaveRemovesTheUncommittedReceiptFile() throws Exception {
        ShopRepository failing = spy(receipts);
        doThrow(new DataAccessResourceFailureException("injected receipt insert failure"))
            .when(failing).createReceipt(anyInt(), anyString(), anyString(), any(), any(), any(), anyString());
        controller = controller(receiptService, failing, new UploadService());
        assertThatThrownBy(() -> upload(png(Color.ORANGE), "10.0.0.8"))
            .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(receiptCount()).isZero();
        assertThat(fileCount()).isZero();
    }
    @Test
    void proxiedCommitFailureRemovesFileAndDoesNotCreateReceipt() throws Exception {
        controller = transactional(controller(receiptService), new CommitFailingTransactionManager(dataSource));
        assertThatThrownBy(() -> upload(png(Color.CYAN), "10.0.0.9"))
            .isInstanceOf(TransactionSystemException.class);
        assertThat(receiptCount()).isZero();
        assertThat(fileCount()).isZero();
    }
    @Test
    void proxiedSuccessfulCommitKeepsThePersistedReceiptFile() throws Exception {
        controller = transactional(controller(receiptService), txManager);
        upload(png(Color.YELLOW), "10.0.0.10");
        assertThat(receiptCount()).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(1);
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
        return controller(ocr, receipts, new UploadService());
    }
    private ShopController controller(ReceiptService ocr, ShopRepository repository, UploadService uploads) {
        return new ShopController(repository, mock(ShopService.class), billing, ocr,
            mock(ForecastService.class), mock(EsgService.class), webhooks,
            mock(NeedsMatchService.class), uploads, new RateLimiter(),
            mock(ru.savefood.shop.LotPhotoReferenceService.class),
            cleanup,
            receiptDir.toString(), receiptDir.toString());
    }
    private static ShopController transactional(ShopController target,
                                                org.springframework.transaction.PlatformTransactionManager manager) {
        TransactionProxyFactoryBean proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(target);
        proxy.setTransactionManager(manager);
        proxy.setProxyTargetClass(true);
        Properties attributes = new Properties();
        attributes.setProperty("uploadReceipt", "PROPAGATION_REQUIRED");
        proxy.setTransactionAttributes(attributes);
        proxy.afterPropertiesSet();
        return (ShopController) proxy.getObject();
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
    private static final class CommitFailingTransactionManager extends DataSourceTransactionManager {
        private CommitFailingTransactionManager(javax.sql.DataSource dataSource) {
            super(dataSource);
            setRollbackOnCommitFailure(true);
        }
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            throw new TransactionSystemException("injected receipt commit failure");
        }
    }
    @FunctionalInterface
    private interface ThrowingUpload {
        void run() throws Exception;
    }
}
