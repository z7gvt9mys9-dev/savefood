package ru.savefood.shop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;

/** Owns the short-lived, validated upload references accepted by JSON lot creation. */
@Service
public class LotPhotoReferenceService {

    private static final Pattern REFERENCE = Pattern.compile("^/uploads/([a-f0-9]{32}\\.(?:jpg|jpeg|png))$");

    private final ShopRepository repo;
    private final UploadService uploads;
    private final LotUploadCleanup cleanup;
    private final Path uploadDir;
    private final LotPhotoStagingProperties properties;

    @Autowired
    public LotPhotoReferenceService(ShopRepository repo, UploadService uploads, LotUploadCleanup cleanup,
                                    @Value("${savefood.shop-upload-dir}") String uploadDir,
                                    LotPhotoStagingProperties properties) {
        this.repo = repo;
        this.uploads = uploads;
        this.cleanup = cleanup;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.properties = properties;
    }

    /** Test/standalone compatibility with the production defaults. */
    public LotPhotoReferenceService(ShopRepository repo, UploadService uploads, LotUploadCleanup cleanup,
                                    String uploadDir) {
        this(repo, uploads, cleanup, uploadDir, new LotPhotoStagingProperties());
    }

    /** Validates/re-encodes an image and returns its server-managed lot-photo reference. */
    @Transactional
    public String stage(int shopId, MultipartFile file) {
        UploadService.PreparedUpload prepared = uploads.prepare(file);
        long byteSize = prepared.content().length;
        if (byteSize > properties.getMaxPendingBytes()) {
            throw quotaExceeded();
        }
        repo.lockLotPhotoStaging(shopId);
        ShopRepository.PendingLotPhotoUsage usage = repo.pendingLotPhotoUsage(shopId);
        long pendingCount = usage == null ? 0 : usage.count();
        long pendingBytes = usage == null ? 0 : usage.bytes();
        if (pendingCount >= properties.getMaxPendingCount()
                || pendingBytes > properties.getMaxPendingBytes() - byteSize) {
            throw quotaExceeded();
        }
        String filename = null;
        try {
            filename = uploads.savePrepared(prepared, uploadDir.toString());
            cleanup.deleteOnRollback(filename);
            repo.stageLotPhotoUpload(shopId, filename, byteSize, properties.getTtl().toMillis());
            return "/uploads/" + filename;
        } catch (RuntimeException e) {
            if (e instanceof UploadService.UploadWriteException failedWrite) {
                filename = failedWrite.filename();
            }
            if (filename != null) {
                cleanup.removeOrQueue(List.of(filename));
            }
            throw e;
        }
    }

    public int uploadRatePerMinute() {
        return properties.getUploadRatePerMinute();
    }

    /** Rejects everything except an existing, unclaimed upload staged by this shop. */
    public String requireAvailable(int shopId, String reference) {
        Matcher matcher = reference == null ? null : REFERENCE.matcher(reference);
        if (matcher == null || !matcher.matches()) {
            throw invalidReference();
        }
        String filename = matcher.group(1);
        Path candidate = uploadDir.resolve(filename).normalize();
        if (!candidate.getParent().equals(uploadDir) || !Files.isRegularFile(candidate)
                || !repo.hasAvailableLotPhotoUpload(shopId, filename)) {
            throw invalidReference();
        }
        return filename;
    }

    private static ApiException invalidReference() {
        return new ApiException(400, "Фотография лота недействительна или уже использована");
    }

    private static ApiException quotaExceeded() {
        return new ApiException(429, "Лимит ожидающих фотографий лота исчерпан");
    }
}
