package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import ru.savefood.billing.BillingService;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.LotUploadCleanup;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.upload.UploadService;

/** Exercises the real quota query and transaction rollback for multipart lots. */
class ShopMultipartLotCreationIT extends PostgresIT {

    @TempDir
    Path uploadDir;

    @Test
    void failedInsertLeavesNoFilesAndDoesNotConsumeMonthlyQuota() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        BillingService billing = new BillingService(jdbc);
        Path unrelated = Files.write(uploadDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"), new byte[] {1});
        List<UploadService.PreparedUpload> photos = List.of(preparedPhoto());
        ShopRepository failingRepo = new ShopRepository(jdbc) {
            @Override
            public int createLotMultiPhoto(int id, String description, double quantity,
                                           java.time.LocalDate expiryDate, List<String> savedPhotos,
                                           String address, String timeSlot, String category, String comment,
                                           boolean requiresCold, String unit, double unitWeightKg) {
                throw new DataIntegrityViolationException("forced insert failure");
            }
        };

        tx.executeWithoutResult(status -> {
            try {
                createService(failingRepo, billing).createLotWithPreparedPhotos(shopId, "food", 1,
                    null, photos, uploadDir.toString(), null, null, null, null, false, "кг", 1);
            } catch (DataIntegrityViolationException expected) {
                status.setRollbackOnly();
            }
        });

        assertThat(billing.lotsCreatedThisMonth(shopId)).isZero();
        assertThat(Files.list(uploadDir).toList()).containsExactly(unrelated);

        int lotId = tx.execute(status -> createService(new ShopRepository(jdbc), billing)
            .createLotWithPreparedPhotos(shopId, "food", 1, null, photos, uploadDir.toString(),
                null, null, null, null, false, "кг", 1));

        assertThat(lotId).isPositive();
        assertThat(billing.lotsCreatedThisMonth(shopId)).isEqualTo(1);
        assertThat(Files.list(uploadDir).toList()).hasSize(2);
    }

    private ShopService createService(ShopRepository repo, BillingService billing) {
        return new ShopService(jdbc, repo, billing, org.mockito.Mockito.mock(NeedyService.class),
            org.mockito.Mockito.mock(PasswordService.class), new UploadService(),
            new LotUploadCleanup(jdbc, uploadDir.toString()));
    }

    private static UploadService.PreparedUpload preparedPhoto() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.GREEN.getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new UploadService().prepare(new org.springframework.mock.web.MockMultipartFile(
            "files", "photo.png", "image/png", bytes.toByteArray()));
    }
}
