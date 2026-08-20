package ru.savefood.upload;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
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
    void malformedImageIsRejected() {
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
    void normalImageIsAccepted() throws Exception {
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

    @Test
    void oversizedDimensionIsRejectedBeforeFullDecode() throws Exception {
        byte[] image = pngHeaderWithDimensions(UploadService.MAX_IMAGE_DIMENSION + 1, 1);
        MockMultipartFile oversized = new MockMultipartFile(
            "file", "oversized.png", "image/png", image);

        assertThatThrownBy(() -> uploads.validateAndSave(oversized, tempDir.toString()))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(413);
                assertThat(e.getMessage()).contains("dimensions");
            });
    }

    @Test
    void excessiveTotalPixelsAreRejected() throws Exception {
        int width = UploadService.MAX_IMAGE_DIMENSION;
        int height = Math.toIntExact(UploadService.MAX_IMAGE_PIXELS / width + 1);
        byte[] image = pngHeaderWithDimensions(width, height);
        MockMultipartFile oversized = new MockMultipartFile(
            "file", "oversized.png", "image/png", image);

        assertThatThrownBy(() -> uploads.validateAndSave(oversized, tempDir.toString()))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(413);
                assertThat(e.getMessage()).contains("too many pixels");
            });
    }

    @Test
    void encodedSizeLimitStillRejectsFilesOverFiveMegabytes() {
        byte[] tooLarge = new byte[Math.toIntExact(UploadService.MAX_UPLOAD_BYTES + 1)];
        MockMultipartFile oversized = new MockMultipartFile(
            "file", "oversized.png", "image/png", tooLarge);

        assertThatThrownBy(() -> uploads.validateAndSave(oversized, tempDir.toString()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(413));
    }

    private static byte[] pngHeaderWithDimensions(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", bytes)).isTrue();
        byte[] png = bytes.toByteArray();
        writeInt(png, 16, width);
        writeInt(png, 20, height);

        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        writeInt(png, 29, (int) crc.getValue());
        return png;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
