package ru.savefood.kyc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Retires legacy recipient KYC files recorded by Flyway V5.
 *
 * <p>The migration removes all live database references immediately, but keeps a
 * durable tombstone until deletion succeeds (or the file is already absent).
 * Failures record the error and a retry time; volunteer storage is never resolved
 * by this service.
 */
@Service
public class NeedyKycDocumentCleanup {

    private static final Logger log = Logger.getLogger(NeedyKycDocumentCleanup.class.getName());

    private final JdbcTemplate jdbc;
    private final Path needyUploadDir;

    public NeedyKycDocumentCleanup(JdbcTemplate jdbc,
            @Value("${savefood.needy-upload-dir:../backend/needy/uploads}") String needyUploadDir) {
        this.jdbc = jdbc;
        this.needyUploadDir = Paths.get(needyUploadDir).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterStartup() {
        cleanupPending();
    }

    @Scheduled(fixedDelay = 60 * 60_000, initialDelay = 60_000)
    public void scheduledCleanup() {
        cleanupPending();
    }

    public void cleanupPending() {
        queueUntrackedFiles();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                "SELECT id, document_ref FROM needy_kyc_document_cleanup "
                + "WHERE completed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP "
                + "ORDER BY id LIMIT 100");
        } catch (RuntimeException e) {
            log.warning("[needy-kyc-cleanup] unable to load tombstones: " + e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String documentRef = (String) row.get("document_ref");
            try {
                Path path = safePath(documentRef);
                if (path == null) {
                    throw new IllegalArgumentException("invalid recipient document reference");
                }
                Files.deleteIfExists(path);
                jdbc.update(
                    "UPDATE needy_kyc_document_cleanup SET attempts = attempts + 1, "
                    + "last_attempt_at = CURRENT_TIMESTAMP, last_error = NULL, "
                    + "completed_at = CURRENT_TIMESTAMP WHERE id = ? AND completed_at IS NULL",
                    id);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                jdbc.update(
                    "UPDATE needy_kyc_document_cleanup SET attempts = attempts + 1, "
                    + "last_attempt_at = CURRENT_TIMESTAMP, last_error = ?, "
                    + "next_attempt_at = CURRENT_TIMESTAMP + "
                    + "make_interval(mins => LEAST(1440, (attempts + 1) * 15)) "
                    + "WHERE id = ? AND completed_at IS NULL",
                    message.substring(0, Math.min(1000, message.length())), id);
                log.warning("[needy-kyc-cleanup] file retained for retry: " + documentRef);
            }
        }
    }

    /**
     * Files left orphaned by the former best-effort deletion path are still
     * recipient KYC because this directory was dedicated to that upload type.
     * Record them durably before attempting deletion.
     */
    private void queueUntrackedFiles() {
        try (var files = Files.list(needyUploadDir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String documentRef = "/needy_uploads/" + path.getFileName();
                try {
                    jdbc.update(
                        "INSERT INTO needy_kyc_document_cleanup (needy_id, document_ref) "
                        + "VALUES (NULL, ?) ON CONFLICT (document_ref) DO NOTHING",
                        documentRef);
                } catch (RuntimeException e) {
                    log.warning("[needy-kyc-cleanup] unable to queue legacy file "
                        + path.getFileName() + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            if (Files.exists(needyUploadDir)) {
                log.warning("[needy-kyc-cleanup] unable to inventory legacy directory: "
                    + e.getMessage());
            }
        }
    }

    private Path safePath(String documentRef) {
        if (documentRef == null || documentRef.isBlank()) {
            return null;
        }
        try {
            Path ref = Paths.get(documentRef);
            Path filename = ref.getFileName();
            if (filename == null || filename.toString().isBlank()) {
                return null;
            }
            Path candidate = needyUploadDir.resolve(filename.toString()).normalize();
            return candidate.getParent() != null && candidate.getParent().equals(needyUploadDir)
                ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
