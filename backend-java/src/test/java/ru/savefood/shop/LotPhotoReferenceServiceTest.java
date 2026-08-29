package ru.savefood.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;

class LotPhotoReferenceServiceTest {

    @TempDir
    Path uploadDir;

    @Test
    void validServerUploadProducesAnAvailableReference() throws Exception {
        ShopRepository repo = mock(ShopRepository.class);
        LotPhotoReferenceService references = references(repo);

        String reference = references.stage(1, validImage());
        String filename = reference.substring("/uploads/".length());
        when(repo.hasAvailableLotPhotoUpload(1, filename)).thenReturn(true);

        assertThat(reference).matches("/uploads/[a-f0-9]{32}\\.(png|jpg|jpeg)");
        assertThat(references.requireAvailable(1, reference)).isEqualTo(filename);
        assertThat(Files.isRegularFile(uploadDir.resolve(filename))).isTrue();
        verify(repo).stageLotPhotoUpload(eq(1), eq(filename), anyLong(), anyLong());
    }

    @Test
    void arbitraryExternalAndNonexistentReferencesAreRejected() {
        ShopRepository repo = mock(ShopRepository.class);
        LotPhotoReferenceService references = references(repo);

        assertInvalid(() -> references.requireAvailable(1, "x"));
        assertInvalid(() -> references.requireAvailable(1, "https://attacker.example/image.png"));
        assertInvalid(() -> references.requireAvailable(1, "/tmp/food.png"));
        assertInvalid(() -> references.requireAvailable(1,
            "/uploads/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"));
    }

    @Test
    void anotherShopsUploadIsRejected() throws Exception {
        ShopRepository repo = mock(ShopRepository.class);
        String filename = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
        Files.write(uploadDir.resolve(filename), new byte[] {1});
        when(repo.hasAvailableLotPhotoUpload(2, filename)).thenReturn(false);

        assertInvalid(() -> references(repo).requireAvailable(2, "/uploads/" + filename));
    }

    @Test
    void malformedImageNeverBecomesAReference() {
        ShopRepository repo = mock(ShopRepository.class);
        MockMultipartFile malformed = new MockMultipartFile("file", "food.png", "image/png",
            "not an image".getBytes());

        assertThatThrownBy(() -> references(repo).stage(1, malformed)).isInstanceOf(ApiException.class);
        verify(repo, never()).stageLotPhotoUpload(eq(1), org.mockito.ArgumentMatchers.anyString(),
            anyLong(), anyLong());
        assertThat(uploadDir).isEmptyDirectory();
    }

    @Test
    void pendingCountLimitRejectsBeforeWritingAFile() throws Exception {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.pendingLotPhotoUsage(1)).thenReturn(new ShopRepository.PendingLotPhotoUsage(10, 1));

        assertThatThrownBy(() -> references(repo).stage(1, validImage()))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(429);
        assertThat(uploadDir).isEmptyDirectory();
    }

    @Test
    void pendingByteLimitRejectsBeforeWritingAFile() throws Exception {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.pendingLotPhotoUsage(1)).thenReturn(
            new ShopRepository.PendingLotPhotoUsage(1, 25L * 1024 * 1024));

        assertThatThrownBy(() -> references(repo).stage(1, validImage()))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(429);
        assertThat(uploadDir).isEmptyDirectory();
    }

    private LotPhotoReferenceService references(ShopRepository repo) {
        return new LotPhotoReferenceService(repo, new UploadService(), mock(LotUploadCleanup.class),
            uploadDir.toString());
    }

    private static MockMultipartFile validImage() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.GREEN.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "food.png", "image/png", out.toByteArray());
    }

    private static void assertInvalid(ThrowingCall call) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
