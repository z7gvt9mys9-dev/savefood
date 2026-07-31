package ru.savefood.upload;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import ru.savefood.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadServiceTest {

    @TempDir
    Path tempDir;

    private final UploadService uploads = new UploadService();

    @Test
    void rejectsArbitraryBytesClaimingToBeAnImage() {
        MockMultipartFile forged = new MockMultipartFile(
            "file", "proof.jpg", "image/jpeg", "not an image".getBytes());

        assertThatThrownBy(() -> uploads.validateAndSave(forged, tempDir.toString()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(415));
    }

    @Test
    void rejectsMismatchedPdfMimeAndImageExtension() {
        MockMultipartFile forged = new MockMultipartFile(
            "file", "proof.jpg", "application/pdf", "%PDF-fake".getBytes());

        assertThatThrownBy(() -> uploads.validateAndSave(forged, tempDir.toString(), true))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(415));
    }

    @Test
    void rejectsWebpUntilASafeCodecIsBundled() {
        MockMultipartFile webp = new MockMultipartFile(
            "file", "photo.webp", "image/webp", new byte[] {0x52, 0x49, 0x46, 0x46});

        assertThatThrownBy(() -> uploads.validateAndSave(webp, tempDir.toString()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(415));
    }

    @Test
    void savesOnlyDecodedAndReencodedImage() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.GREEN.getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", bytes)).isTrue();
        MockMultipartFile valid = new MockMultipartFile("file", "photo.png", "image/png", bytes.toByteArray());

        String filename = uploads.validateAndSave(valid, tempDir.toString());

        assertThat(filename).endsWith(".png");
        try (InputStream saved = Files.newInputStream(tempDir.resolve(filename))) {
            assertThat(ImageIO.read(saved)).isNotNull();
        }
    }
}
