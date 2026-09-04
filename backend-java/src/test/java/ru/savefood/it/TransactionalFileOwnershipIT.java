package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import ru.savefood.audit.AuditService;
import ru.savefood.billing.BillingService;
import ru.savefood.kyc.KycCrypto;
import ru.savefood.kyc.KycService;
import ru.savefood.needy.NeedyService;
import ru.savefood.photo.DeliveryPhotoStorage;
import ru.savefood.photo.PhotoModerationService;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.LotPhotoReferenceService;
import ru.savefood.shop.LotPhotoStagingProperties;
import ru.savefood.shop.LotUploadCleanup;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.storage.SensitiveFileCleanup;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.volunteer.VolunteerController;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;
/** Focused transaction-completion coverage for filesystem objects created by lot/KYC writes. */
class TransactionalFileOwnershipIT extends PostgresIT {
    @TempDir
    Path tempDir;
    private Path lotDir;
    private Path kycDir;
    private Path needyDir;
    private Path deliveryDir;
    private Path legacyDir;
    @BeforeEach
    void createStorageRoots() throws Exception {
        lotDir = Files.createDirectory(tempDir.resolve("lots"));
        kycDir = Files.createDirectory(tempDir.resolve("kyc"));
        needyDir = Files.createDirectory(tempDir.resolve("needy"));
        deliveryDir = Files.createDirectory(tempDir.resolve("delivery"));
        legacyDir = Files.createDirectory(tempDir.resolve("legacy"));
    }
    @Test
    void committedLotTransactionKeepsCreatedFileAndDoesNotQueueCleanup() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        int lotId = lotService().createLotWithPreparedPhotos(shopId, "food", 1, null,
            List.of(preparedPhoto()), lotDir.toString(), null, null, null, null, false, "кг", 1);
        assertThat(lotId).isPositive();
        assertThat(Files.list(lotDir).toList()).hasSize(1);
        assertThat(count("shop_upload_cleanup")).isZero();
    }
    @Test
    void exceptionInsideLotTransactionDeletesCreatedFile() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        ShopRepository failing = new ShopRepository(jdbc) {
            @Override
            public int createLotMultiPhoto(int id, String description, double quantity,
                    java.time.LocalDate expiryDate, List<String> photos, String address,
                    String timeSlot, String category, String comment, boolean requiresCold,
                    String unit, double unitWeightKg) {
                throw new DataIntegrityViolationException("forced body failure");
            }
        };
        assertThatThrownBy(() -> lotService(failing).createLotWithPreparedPhotos(shopId, "food", 1,
            null, List.of(preparedPhoto()), lotDir.toString(), null, null, null, null,
            false, "кг", 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(Files.list(lotDir).toList()).isEmpty();
        assertThat(count("shop_upload_cleanup")).isZero();
    }
    @Test
    void outerRollbackAfterInnerLotMethodReturnsDeletesCreatedFile() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        ShopService service = lotService();
        tx.executeWithoutResult(status -> {
            int lotId = service.createLotWithPreparedPhotos(shopId, "food", 1, null,
                List.of(preparedPhoto()), lotDir.toString(), null, null, null, null,
                false, "кг", 1);
            assertThat(lotId).isPositive();
            assertThat(list(lotDir)).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(Files.list(lotDir).toList()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE shop_id = ?",
            Integer.class, shopId)).isZero();
    }
    @Test
    void deferredPostgresFailureAtProxyCommitDeletesCreatedLotFile() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        jdbc.execute("CREATE FUNCTION fail_lot_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'forced deferred lot failure'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER fail_lot_commit AFTER INSERT ON lots "
            + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_lot_commit()");
        assertThatThrownBy(() -> lotService().createLotWithPreparedPhotos(shopId, "food", 1,
            null, List.of(preparedPhoto()), lotDir.toString(), null, null, null, null,
            false, "кг", 1))
            .isInstanceOf(RuntimeException.class)
            .hasStackTraceContaining("forced deferred lot failure");
        assertThat(Files.list(lotDir).toList()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE shop_id = ?",
            Integer.class, shopId)).isZero();
        assertThat(count("shop_upload_cleanup")).isZero();
    }
    @Test
    void rollbackOfStagedPhotoClaimKeepsPreExistingStagedFileUsable() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        LotPhotoStagingProperties limits = stagingLimits();
        LotUploadCleanup cleanup = lotCleanup(limits);
        LotPhotoReferenceService references = proxied(new LotPhotoReferenceService(
            new ShopRepository(jdbc), new UploadService(), cleanup, lotDir.toString(), limits));
        String reference = references.stage(shopId, validImage());
        String filename = references.requireAvailable(shopId, reference);
        ShopService service = lotService(new ShopRepository(jdbc), cleanup);
        tx.executeWithoutResult(status -> {
            service.createLotWithClaimedPhoto(shopId, "food", 1, null, filename,
                null, null, null, null, false, "кг", 1);
            status.setRollbackOnly();
        });
        assertThat(lotDir.resolve(filename)).isRegularFile();
        assertThat(references.requireAvailable(shopId, reference)).isEqualTo(filename);
        Map<String, Object> staged = jdbc.queryForMap(
            "SELECT lot_id, byte_size FROM shop_lot_photo_uploads WHERE filename = ?", filename);
        assertThat(staged.get("lot_id")).isNull();
        assertThat(((Number) staged.get("byte_size")).longValue()).isPositive();
    }
    @Test
    void outerRollbackOfStagingDeletesTheNewFileAndReference() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        LotPhotoStagingProperties limits = stagingLimits();
        LotPhotoReferenceService references = proxied(new LotPhotoReferenceService(
            new ShopRepository(jdbc), new UploadService(), lotCleanup(limits), lotDir.toString(), limits));
        tx.executeWithoutResult(status -> {
            assertThat(references.stage(shopId, validImage())).startsWith("/uploads/");
            assertThat(list(lotDir)).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(Files.list(lotDir).toList()).isEmpty();
        assertThat(count("shop_lot_photo_uploads")).isZero();
        assertThat(count("shop_upload_cleanup")).isZero();
    }
    @Test
    void committedKycTransactionKeepsExactNewGenerationAndDoesNotQueueCleanup() throws Exception {
        int volunteerId = insertVolunteer("Volunteer");
        VolunteerController controller = kycController();
        byte[] pdf = "%PDF-1.4\nidentity".getBytes(StandardCharsets.UTF_8);
        controller.uploadDocument(volunteerId, pdf(pdf), volunteerUser(volunteerId), request());
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT document, kyc_generation FROM volunteers WHERE id = ?", volunteerId);
        String document = (String) row.get("document");
        String generation = (String) row.get("kyc_generation");
        UUID.fromString(generation);
        Path exactFile = kycDir.resolve(document.substring("/volunteer_kyc/".length()));
        assertThat(exactFile).isRegularFile();
        assertThat(kycCrypto().readDecrypted(exactFile.toString())).isEqualTo(pdf);
        assertThat(count("sensitive_file_cleanup")).isZero();
    }
    @Test
    void failedRollbackDeleteQueuesExactKycPathAndDelayedRetryCannotDeleteReplacement() throws Exception {
        int volunteerId = insertVolunteer("Volunteer");
        Path original = Files.write(kycDir.resolve("original.pdf"), new byte[] {1});
        jdbc.update("UPDATE volunteers SET status = 'pending', document = ?, kyc_generation = ? WHERE id = ?",
            "/volunteer_kyc/original.pdf", "generation-original", volunteerId);
        SensitiveFileCleanup cleanup = sensitiveCleanup();
        VolunteerController controller = kycController(cleanup, kycCrypto());
        String[] rolledBackDocument = new String[1];
        tx.executeWithoutResult(status -> {
            controller.uploadDocument(volunteerId, pdf("%PDF-1.4\nrollback".getBytes(StandardCharsets.UTF_8)),
                volunteerUser(volunteerId), request());
            rolledBackDocument[0] = jdbc.queryForObject(
                "SELECT document FROM volunteers WHERE id = ?", String.class, volunteerId);
            Path rolledBackPath = kycDir.resolve(
                rolledBackDocument[0].substring("/volunteer_kyc/".length()));
            try {
                Files.delete(rolledBackPath);
                Files.createDirectory(rolledBackPath);
                Files.write(rolledBackPath.resolve("blocker"), new byte[] {1});
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            status.setRollbackOnly();
        });
        assertThat(original).isRegularFile();
        assertThat(jdbc.queryForMap("SELECT document, kyc_generation FROM volunteers WHERE id = ?", volunteerId))
            .containsEntry("document", "/volunteer_kyc/original.pdf")
            .containsEntry("kyc_generation", "generation-original");
        assertThat(jdbc.queryForMap(
            "SELECT storage_type, file_ref, completed_at FROM sensitive_file_cleanup WHERE file_ref = ?",
            rolledBackDocument[0]))
            .containsEntry("storage_type", "volunteer_kyc")
            .containsEntry("file_ref", rolledBackDocument[0])
            .containsEntry("completed_at", null);
        controller.uploadDocument(volunteerId, pdf("%PDF-1.4\nreplacement".getBytes(StandardCharsets.UTF_8)),
            volunteerUser(volunteerId), request());
        String replacement = jdbc.queryForObject(
            "SELECT document FROM volunteers WHERE id = ?", String.class, volunteerId);
        Path replacementPath = kycDir.resolve(replacement.substring("/volunteer_kyc/".length()));
        Path rolledBackPath = kycDir.resolve(
            rolledBackDocument[0].substring("/volunteer_kyc/".length()));
        Files.delete(rolledBackPath.resolve("blocker"));
        jdbc.update("UPDATE sensitive_file_cleanup SET next_attempt_at = CURRENT_TIMESTAMP WHERE file_ref = ?",
            rolledBackDocument[0]);
        cleanup.retryPending();
        assertThat(rolledBackPath).doesNotExist();
        assertThat(replacementPath).isRegularFile();
        assertThat(jdbc.queryForObject("SELECT document FROM volunteers WHERE id = ?",
            String.class, volunteerId)).isEqualTo(replacement);
    }
    private ShopService lotService() {
        LotUploadCleanup cleanup = lotCleanup(stagingLimits());
        return lotService(new ShopRepository(jdbc), cleanup);
    }
    private ShopService lotService(ShopRepository repo) {
        return lotService(repo, lotCleanup(stagingLimits()));
    }
    private ShopService lotService(ShopRepository repo, LotUploadCleanup cleanup) {
        return proxied(new ShopService(jdbc, repo, new BillingService(jdbc), mock(NeedyService.class),
            mock(PasswordService.class), new UploadService(), cleanup));
    }
    private LotUploadCleanup lotCleanup(LotPhotoStagingProperties limits) {
        return new LotUploadCleanup(jdbc, txManager, lotDir.toString(), limits);
    }
    private VolunteerController kycController() {
        return kycController(sensitiveCleanup(), kycCrypto());
    }
    private VolunteerController kycController(SensitiveFileCleanup cleanup, KycCrypto crypto) {
        return proxied(new VolunteerController(new VolunteerRepository(jdbc), mock(VolunteerService.class),
            mock(RateLimiter.class), new UploadService(), crypto, mock(KycService.class),
            mock(PhotoModerationService.class), mock(WebhookService.class), mock(TelegramService.class),
            jdbc, mock(AuditService.class), cleanup, mock(DeliveryPhotoStorage.class), true,
            kycDir.toString(), kycDir.toString()));
    }
    private SensitiveFileCleanup sensitiveCleanup() {
        return new SensitiveFileCleanup(jdbc, needyDir.toString(), kycDir.toString(),
            deliveryDir.toString(), legacyDir.toString(), txManager);
    }
    private KycCrypto kycCrypto() {
        String key = Base64.getUrlEncoder().encodeToString(new byte[32]);
        return new KycCrypto(key, false, new MockEnvironment());
    }
    @SuppressWarnings("unchecked")
    private <T> T proxied(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
            txManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
    private static List<Path> list(Path directory) {
        try (var files = Files.list(directory)) {
            return files.toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private static LotPhotoStagingProperties stagingLimits() {
        LotPhotoStagingProperties limits = new LotPhotoStagingProperties();
        limits.setMaxPendingCount(10);
        limits.setMaxPendingBytes(25L * 1024 * 1024);
        limits.setTtl(Duration.ofMinutes(45));
        return limits;
    }
    private static UploadService.PreparedUpload preparedPhoto() {
        return new UploadService().prepare(validImage());
    }
    private static MockMultipartFile validImage() {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, Color.GREEN.getRGB());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return new MockMultipartFile("file", "food.png", "image/png", bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private static MockMultipartFile pdf(byte[] content) {
        return new MockMultipartFile("file", "identity.pdf", "application/pdf", content);
    }
    private static CurrentUser volunteerUser(int volunteerId) {
        return new CurrentUser(volunteerId, "volunteer", "volunteer", volunteerId);
    }
    private static HttpServletRequest request() {
        return mock(HttpServletRequest.class);
    }
}
