package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import ru.savefood.billing.BillingService;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.LotPhotoReferenceService;
import ru.savefood.shop.LotPhotoStagingProperties;
import ru.savefood.shop.LotUploadCleanup;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;

class LotPhotoStagingIT extends PostgresIT {

    @TempDir
    Path uploadDir;

    @Test
    void concurrentUploadsRespectPendingCountQuota() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        LotPhotoStagingProperties limits = limits(1, 25L * 1024 * 1024, Duration.ofMinutes(45), 100);
        LotPhotoReferenceService references = references(limits);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        new TransactionTemplate(txManager).execute(status -> references.stage(shopId, validImage()));
                        return true;
                    } catch (ApiException e) {
                        assertThat(e.getStatus()).isEqualTo(429);
                        return false;
                    }
                }));
            }
            start.countDown();
            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                .containsExactlyInAnyOrder(true, false);
        }

        assertThat(pendingCount(shopId)).isEqualTo(1);
        assertThat(Files.list(uploadDir).toList()).hasSize(1);
    }

    @Test
    void byteQuotaIsExactAndClaimReleasesItForNormalStageCreateFlow() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        long encodedBytes = new UploadService().prepare(validImage()).content().length;
        LotPhotoStagingProperties limits = limits(10, encodedBytes, Duration.ofMinutes(45), 100);
        LotPhotoReferenceService references = references(limits);

        String first = stage(references, shopId);
        assertThat(jdbc.queryForObject("SELECT byte_size FROM shop_lot_photo_uploads WHERE shop_id = ?",
            Long.class, shopId)).isEqualTo(encodedBytes);
        assertThatThrownBy(() -> stage(references, shopId))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(429);

        String filename = references.requireAvailable(shopId, first);
        int lotId = tx.execute(status -> shopService(new ShopRepository(jdbc), limits)
            .createLotWithClaimedPhoto(shopId, "food", 1, null, filename, null, null,
                null, null, false, "кг", 1));

        assertThat(lotId).isPositive();
        assertThat(jdbc.queryForObject("SELECT lot_id FROM shop_lot_photo_uploads WHERE filename = ?",
            Integer.class, filename)).isEqualTo(lotId);
        assertThat(stage(references, shopId)).startsWith("/uploads/");
        assertThat(pendingCount(shopId)).isEqualTo(1);
    }

    @Test
    void staleReferenceExpiresDeletesFileAndReleasesQuotaWithoutTouchingAnotherShop() throws Exception {
        int staleShop = insertShop("Stale", 43.238, 76.889);
        int otherShop = insertShop("Other", 43.239, 76.890);
        LotPhotoStagingProperties limits = limits(1, 25L * 1024 * 1024, Duration.ofMinutes(45), 100);
        LotPhotoReferenceService references = references(limits);
        String stale = stage(references, staleShop);
        String other = stage(references, otherShop);
        String staleFilename = filename(stale);
        String otherFilename = filename(other);
        expire(staleFilename);

        assertThatThrownBy(() -> references.requireAvailable(staleShop, stale))
            .isInstanceOf(ApiException.class);
        tx.executeWithoutResult(status -> cleanup(limits).cleanupExpiredStagedPhotos());

        assertThat(uploadDir.resolve(staleFilename)).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_lot_photo_uploads WHERE filename = ?",
            Integer.class, staleFilename)).isZero();
        assertThat(uploadDir.resolve(otherFilename)).isRegularFile();
        assertThat(jdbc.queryForObject("SELECT shop_id FROM shop_lot_photo_uploads WHERE filename = ?",
            Integer.class, otherFilename)).isEqualTo(otherShop);
        assertThat(stage(references, staleShop)).startsWith("/uploads/");
    }

    @Test
    void staleCleanupProcessesOnlyTheConfiguredBatch() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        LotPhotoStagingProperties limits = limits(10, 25L * 1024 * 1024, Duration.ofMinutes(45), 1);
        LotPhotoReferenceService references = references(limits);
        String first = filename(stage(references, shopId));
        String second = filename(stage(references, shopId));
        expire(first);
        expire(second);

        tx.executeWithoutResult(status -> cleanup(limits).cleanupExpiredStagedPhotos());

        assertThat(pendingCount(shopId)).isEqualTo(1);
        assertThat(Files.list(uploadDir).toList()).hasSize(1);
    }

    @Test
    void claimedReferenceWinsCleanupRaceAndIsNeverCleaned() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        LotPhotoStagingProperties limits = limits(1, 25L * 1024 * 1024, Duration.ofMinutes(45), 100);
        LotPhotoReferenceService references = references(limits);
        String reference = stage(references, shopId);
        String filename = references.requireAvailable(shopId, reference);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        ShopRepository lockingRepo = new ShopRepository(jdbc) {
            @Override
            public boolean claimLotPhotoUpload(int ownerId, String stagedFilename, int lotId) {
                boolean result = super.claimLotPhotoUpload(ownerId, stagedFilename, lotId);
                claimed.countDown();
                try {
                    releaseClaim.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            }
        };

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> created = pool.submit(() -> new TransactionTemplate(txManager).execute(status ->
                shopService(lockingRepo, limits).createLotWithClaimedPhoto(shopId, "food", 1, null,
                    filename, null, null, null, null, false, "кг", 1)));
            claimed.await();
            tx.executeWithoutResult(status -> cleanup(limits).cleanupExpiredStagedPhotos());
            releaseClaim.countDown();
            assertThat(created.get()).isPositive();
        }

        jdbc.update("UPDATE shop_lot_photo_uploads SET expires_at = clock_timestamp() - INTERVAL '1 minute' "
            + "WHERE filename = ?", filename);
        tx.executeWithoutResult(status -> cleanup(limits).cleanupExpiredStagedPhotos());
        assertThat(uploadDir.resolve(filename)).isRegularFile();
        assertThat(jdbc.queryForObject("SELECT lot_id IS NOT NULL FROM shop_lot_photo_uploads WHERE filename = ?",
            Boolean.class, filename)).isTrue();
    }

    @Test
    void cleanupWinningBeforeClaimRollsBackLotAndLeavesNoDeletedImageReference() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        LotPhotoStagingProperties limits = limits(10, 25L * 1024 * 1024, Duration.ofMinutes(45), 100);
        LotPhotoReferenceService references = references(limits);
        String reference = stage(references, shopId);
        String filename = references.requireAvailable(shopId, reference);
        expire(filename);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> claim = pool.submit(() -> {
                start.await();
                try {
                    new TransactionTemplate(txManager).execute(status -> shopService(new ShopRepository(jdbc), limits)
                        .createLotWithClaimedPhoto(shopId, "food", 1, null, filename, null, null,
                            null, null, false, "кг", 1));
                    return true;
                } catch (ApiException e) {
                    assertThat(e.getStatus()).isEqualTo(400);
                    return false;
                }
            });
            Future<?> cleanup = pool.submit(() -> {
                start.await();
                new TransactionTemplate(txManager).executeWithoutResult(
                    status -> cleanup(limits).cleanupExpiredStagedPhotos());
                return null;
            });
            start.countDown();
            assertThat(claim.get()).isFalse();
            cleanup.get();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE shop_id = ?", Integer.class, shopId))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_lot_photo_uploads WHERE filename = ?",
            Integer.class, filename)).isZero();
        assertThat(uploadDir.resolve(filename)).doesNotExist();
    }

    @Test
    void failedStaleFileDeleteRemainsDurableAndIsRetried() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        LotPhotoStagingProperties limits = limits(10, 25L * 1024 * 1024, Duration.ofMinutes(45), 100);
        LotPhotoReferenceService references = references(limits);
        String filename = filename(stage(references, shopId));
        expire(filename);
        Path stagedPath = uploadDir.resolve(filename);
        Files.delete(stagedPath);
        Files.createDirectory(stagedPath);
        Files.write(stagedPath.resolve("child"), new byte[] {1});

        tx.executeWithoutResult(status -> cleanup(limits).cleanupExpiredStagedPhotos());

        assertThat(jdbc.queryForObject("SELECT cleanup_attempts FROM shop_lot_photo_uploads WHERE filename = ?",
            Integer.class, filename)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cleanup_last_error FROM shop_lot_photo_uploads WHERE filename = ?",
            String.class, filename)).isEqualTo("delete failed");
        assertThat(stagedPath).exists();

        Files.delete(stagedPath.resolve("child"));
        jdbc.update("UPDATE shop_lot_photo_uploads SET cleanup_next_attempt_at = clock_timestamp() - INTERVAL '1 second' "
            + "WHERE filename = ?", filename);
        tx.executeWithoutResult(status -> cleanup(limits).cleanupExpiredStagedPhotos());

        assertThat(stagedPath).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_lot_photo_uploads WHERE filename = ?",
            Integer.class, filename)).isZero();
    }

    private LotPhotoReferenceService references(LotPhotoStagingProperties limits) {
        return new LotPhotoReferenceService(new ShopRepository(jdbc), new UploadService(), cleanup(limits),
            uploadDir.toString(), limits);
    }

    private LotUploadCleanup cleanup(LotPhotoStagingProperties limits) {
        return new LotUploadCleanup(jdbc, uploadDir.toString(), limits);
    }

    private ShopService shopService(ShopRepository repo, LotPhotoStagingProperties limits) {
        return new ShopService(jdbc, repo, new BillingService(jdbc), mock(NeedyService.class),
            mock(PasswordService.class), new UploadService(), cleanup(limits));
    }

    private String stage(LotPhotoReferenceService references, int shopId) {
        return tx.execute(status -> references.stage(shopId, validImage()));
    }

    private void expire(String filename) {
        jdbc.update("UPDATE shop_lot_photo_uploads SET expires_at = clock_timestamp() - INTERVAL '1 second', "
            + "cleanup_next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE filename = ?", filename);
    }

    private int pendingCount(int shopId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shop_lot_photo_uploads "
            + "WHERE shop_id = ? AND lot_id IS NULL", Integer.class, shopId);
    }

    private static String filename(String reference) {
        return reference.substring("/uploads/".length());
    }

    private static LotPhotoStagingProperties limits(int count, long bytes, Duration ttl, int batch) {
        LotPhotoStagingProperties limits = new LotPhotoStagingProperties();
        limits.setMaxPendingCount(count);
        limits.setMaxPendingBytes(bytes);
        limits.setTtl(ttl);
        limits.setCleanupBatchSize(batch);
        return limits;
    }

    private static MockMultipartFile validImage() {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, Color.GREEN.getRGB());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return new MockMultipartFile("file", "food.png", "image/png", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
