package ru.savefood.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.forecast.ForecastService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.needy.NeedyService;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.PasswordService;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;

class ShopMultipartLotCreationTest {

    @TempDir
    Path uploadDir;

    @Test
    void quotaRejectionLeavesUploadDirectoryUnchanged() throws Exception {
        BillingService billing = mock(BillingService.class);
        doThrow(new ApiException(402, "quota reached")).when(billing).acquireLotQuota(1);
        ShopService service = service(mock(ShopRepository.class), billing);
        Path unrelated = Files.write(uploadDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"), new byte[] {1});

        assertThatThrownBy(() -> create(service, List.of(prepared(validImage("first.png")))))
            .isInstanceOf(ApiException.class);

        assertThat(Files.list(uploadDir).toList()).containsExactly(unrelated);
    }

    @Test
    void validFirstImageAndInvalidSecondImageWriteNothing() throws Exception {
        ShopService service = mock(ShopService.class);
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.getShopById(1)).thenReturn(Map.of("kind", "business"));
        ShopController controller = controller(repo, service);
        Path unrelated = Files.write(uploadDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"), new byte[] {1});
        MockMultipartFile invalid = new MockMultipartFile("files", "bad.png", "image/png", "not image".getBytes());

        assertThatThrownBy(() -> controller.createLotUpload(1, "food", 1, "кг", 1,
            null, null, null, null, null, false, null,
            List.of(validImage("first.png"), invalid), shopUser()))
            .isInstanceOf(ApiException.class);

        assertThat(Files.list(uploadDir).toList()).containsExactly(unrelated);
        verifyNoInteractions(service);
    }

    @Test
    void validationFailureAfterSafeStagingWritesNothing() throws Exception {
        ShopService service = mock(ShopService.class);
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.getShopById(1)).thenReturn(Map.of("kind", "business"));
        ShopController controller = controller(repo, service);
        Path unrelated = Files.write(uploadDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"), new byte[] {1});

        assertThatThrownBy(() -> controller.createLotUpload(1, "food", 1, "кг", 1,
            null, null, null, "not-a-category", null, false, null,
            List.of(validImage("first.png")), shopUser()))
            .isInstanceOf(ApiException.class);

        assertThat(Files.list(uploadDir).toList()).containsExactly(unrelated);
        verifyNoInteractions(service);
    }

    @Test
    void fractionalMultipartQuantityIsRejectedBeforeAnyUploadOrLotCreation() throws Exception {
        ShopService service = mock(ShopService.class);
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.getShopById(1)).thenReturn(Map.of("kind", "business"));
        ShopController controller = controller(repo, service);

        assertThatThrownBy(() -> controller.createLotUpload(1, "food", 2.5, "кг", 1,
            null, null, null, null, null, false, null,
            List.of(validImage("first.png")), shopUser()))
            .isInstanceOf(ApiException.class);

        verifyNoInteractions(service);
    }

    @Test
    void databaseFailureDeletesOnlyCurrentRequestFiles() throws Exception {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.createLotMultiPhoto(anyInt(), anyString(), anyDouble(), any(), anyList(), any(), any(), any(),
            any(), anyBoolean(), anyString(), anyDouble()))
            .thenThrow(new DataIntegrityViolationException("forced insert failure"));
        ShopService service = service(repo, mock(BillingService.class));
        Path unrelated = Files.write(uploadDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"), new byte[] {1});

        assertThatThrownBy(() -> create(service, List.of(prepared(validImage("first.png")))))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(Files.list(uploadDir).toList()).containsExactly(unrelated);
    }

    @Test
    void successfulMultipartCreationKeepsExactlyItsFiles() throws Exception {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.createLotMultiPhoto(eq(1), anyString(), anyDouble(), any(), anyList(), any(), any(), any(),
            any(), anyBoolean(), anyString(), anyDouble()))
            .thenReturn(17);
        ShopService service = service(repo, mock(BillingService.class));
        Path unrelated = Files.write(uploadDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"), new byte[] {1});

        assertThat(create(service, List.of(prepared(validImage("first.png")), prepared(validImage("second.png")))))
            .isEqualTo(17);

        List<Path> files = Files.list(uploadDir).toList();
        assertThat(files).contains(unrelated);
        assertThat(files).hasSize(3);
        verify(repo).createLotMultiPhoto(eq(1), anyString(), anyDouble(), any(),
            org.mockito.ArgumentMatchers.argThat(photos -> photos.size() == 2
                && photos.stream().allMatch(photo -> photo.matches("/uploads/[a-f0-9]{32}\\.(png|jpg|jpeg)"))),
            any(), any(), any(), any(), anyBoolean(), anyString(), anyDouble());
    }

    private ShopService service(ShopRepository repo, BillingService billing) {
        return new ShopService(mock(JdbcTemplate.class), repo, billing, mock(NeedyService.class),
            mock(PasswordService.class), new UploadService(), new LotUploadCleanup(mock(JdbcTemplate.class), uploadDir.toString()));
    }

    private int create(ShopService service, List<UploadService.PreparedUpload> photos) {
        return service.createLotWithPreparedPhotos(1, "food", 1, null, photos, uploadDir.toString(),
            null, null, null, null, false, "кг", 1);
    }

    private UploadService.PreparedUpload prepared(MockMultipartFile file) {
        return new UploadService().prepare(file);
    }

    private ShopController controller(ShopRepository repo, ShopService service) {
        return new ShopController(repo, service, mock(BillingService.class), mock(ReceiptService.class),
            mock(ForecastService.class), mock(EsgService.class), mock(WebhookService.class),
            mock(NeedsMatchService.class), new UploadService(), mock(RateLimiter.class),
            mock(LotPhotoReferenceService.class),
            uploadDir.toString(), uploadDir.toString());
    }

    private static CurrentUser shopUser() {
        return new CurrentUser(1, "shop", "shop", 1);
    }

    private static MockMultipartFile validImage(String name) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.GREEN.getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("files", name, "image/png", bytes.toByteArray());
    }
}
