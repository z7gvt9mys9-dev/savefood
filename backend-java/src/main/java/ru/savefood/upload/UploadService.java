package ru.savefood.upload;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import ru.savefood.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Port of utils.py {@code validate_and_save_upload}: validate MIME type,
 * extension and size, strip image metadata (EXIF GPS/orientation) by
 * re-encoding, then save under a random filename. Returns the new filename.
 * Failures throw {@link ApiException} with the same status codes as the Python
 * {@code UploadValidationError} (400/413/415).
 */
@Service
public class UploadService {

    // The stock JDK ImageIO codecs can decode and safely re-encode JPEG/PNG,
    // but do not provide a WebP reader/writer. Do not advertise WebP until a
    // vetted codec is shipped: accepting it would either fail every honest
    // upload or tempt a fallback that preserves untrusted EXIF bytes.
    private static final Set<String> ALLOWED_IMAGE_TYPES =
        Set.of("image/jpeg", "image/jpg", "image/png");
    private static final Set<String> ALLOWED_IMAGE_EXTS = Set.of(".jpg", ".jpeg", ".png");
    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    static final int MAX_IMAGE_DIMENSION = 8_192;
    static final long MAX_IMAGE_PIXELS = 25_000_000L;

    public String validateAndSave(MultipartFile file, String destDir) {
        return validateAndSave(file, destDir, false);
    }

    /**
     * As {@link #validateAndSave(MultipartFile, String)} but, when {@code allowPdf}
     * is true, also accepts PDFs (KYC documents). PDFs are validated by magic bytes
     * ({@code %PDF}) and stored verbatim — only images are re-encoded to strip EXIF.
     */
    public String validateAndSave(MultipartFile file, String destDir, boolean allowPdf) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || file.getOriginalFilename().isEmpty()) {
            throw new ApiException(400, "No file uploaded");
        }
        Set<String> allowedTypes = allowPdf
            ? union(ALLOWED_IMAGE_TYPES, "application/pdf") : ALLOWED_IMAGE_TYPES;
        Set<String> allowedExts = allowPdf ? union(ALLOWED_IMAGE_EXTS, ".pdf") : ALLOWED_IMAGE_EXTS;

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!contentType.isEmpty() && !allowedTypes.contains(contentType)) {
            throw new ApiException(415, "Unsupported file type: " + contentType);
        }
        String ext = extension(file.getOriginalFilename());
        if (!allowedExts.contains(ext)) {
            throw new ApiException(415, "Unsupported file extension: " + (ext.isEmpty() ? "<none>" : ext));
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(400, "Invalid or corrupted image");
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new ApiException(413, "File too large (limit " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB)");
        }

        boolean isPdf = ".pdf".equals(ext);
        if ("application/pdf".equals(contentType) != isPdf) {
            throw new ApiException(415, "File extension and MIME type do not match");
        }
        if (!isPdf && !contentType.isEmpty() && !matchesImageType(ext, contentType)) {
            throw new ApiException(415, "File extension and MIME type do not match");
        }
        // A .pdf extension / content-type alone is attacker-controlled — real PDFs
        // start with "%PDF". Images are re-encoded below, so they self-validate.
        if (allowPdf && isPdf
                && !(content.length >= 4 && content[0] == '%' && content[1] == 'P'
                     && content[2] == 'D' && content[3] == 'F')) {
            throw new ApiException(415, "File is not a valid PDF");
        }

        // Strip metadata (privacy): re-encode so EXIF GPS/orientation can't survive
        // onto the public impact feed.  An image decoder/writer must be present;
        // accepting the original bytes on a WebP fallback would both retain EXIF
        // and let arbitrary bytes masquerade as an image. PDFs are stored verbatim.
        byte[] toWrite = isPdf ? content : reencode(content, ext);

        try {
            Path dir = Paths.get(destDir);
            Files.createDirectories(dir);
            String safeName = UUID.randomUUID().toString().replace("-", "") + ext;
            Files.write(dir.resolve(safeName), toWrite);
            return safeName;
        } catch (IOException e) {
            throw new ApiException(500, "Could not store upload");
        }
    }

    private static byte[] reencode(byte[] content, String ext) {
        String fmt = switch (ext) {
            case ".png" -> "png";
            default -> "jpeg";
        };
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new ApiException(415, "File is not a supported image");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ApiException(415, "File is not a supported image");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);

                BufferedImage img = reader.read(0);
                if (img == null) {
                    throw new ApiException(415, "File is not a supported image");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(img, fmt, out)) {
                    throw new ApiException(415, "Image format cannot be safely processed");
                }
                return out.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ApiException(400, "Invalid or corrupted image");
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new ApiException(400, "Invalid image dimensions");
        }
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
            throw new ApiException(413, "Image dimensions are too large");
        }
        if ((long) width * height > MAX_IMAGE_PIXELS) {
            throw new ApiException(413, "Image has too many pixels");
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }

    private static boolean matchesImageType(String ext, String contentType) {
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg".equals(contentType) || "image/jpg".equals(contentType);
            case ".png" -> "image/png".equals(contentType);
            default -> false;
        };
    }

    private static Set<String> union(Set<String> base, String extra) {
        Set<String> s = new java.util.HashSet<>(base);
        s.add(extra);
        return s;
    }
}
