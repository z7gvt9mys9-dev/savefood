package ru.savefood.shop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
@Service
public class LotUploadCleanup {
    private static final Logger log = Logger.getLogger(LotUploadCleanup.class.getName());
    private static final String GENERATED_IMAGE = "[a-f0-9]{32}\\.(jpg|jpeg|png)";
    private final JdbcTemplate jdbc;
    private final Path uploadDir;
    private final LotPhotoStagingProperties stagingProperties;
    private final TransactionTemplate cleanupTransaction;
    @Autowired
    public LotUploadCleanup(JdbcTemplate jdbc,
                            @Value("${savefood.shop-upload-dir}") String uploadDir,
                            LotPhotoStagingProperties stagingProperties,
                            PlatformTransactionManager transactionManager) {
        this(jdbc, uploadDir, stagingProperties, requiresNew(transactionManager));
    }
    public LotUploadCleanup(JdbcTemplate jdbc, String uploadDir,
                            LotPhotoStagingProperties stagingProperties) {
        this(jdbc, uploadDir, stagingProperties, (TransactionTemplate) null);
    }
    public LotUploadCleanup(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            String uploadDir, LotPhotoStagingProperties stagingProperties) {
        this(jdbc, uploadDir, stagingProperties, requiresNew(transactionManager));
    }
    private LotUploadCleanup(JdbcTemplate jdbc, String uploadDir,
                             LotPhotoStagingProperties stagingProperties,
                             TransactionTemplate cleanupTransaction) {
        this.jdbc = jdbc;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.stagingProperties = stagingProperties;
        this.cleanupTransaction = cleanupTransaction;
    }
    /** Test/standalone compatibility with the production defaults. */
    public LotUploadCleanup(JdbcTemplate jdbc, String uploadDir) {
        this(jdbc, uploadDir, new LotPhotoStagingProperties(), (TransactionTemplate) null);
    }
    public boolean deleteOnRollback(String filename) {
        if (safePath(filename) == null) {
            throw new IllegalArgumentException("Invalid generated upload filename");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Transaction synchronization is not active");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED && !delete(filename)) {
                    queueFailure(filename);
                }
            }
        });
        return true;
    }
    /** Attempt deletion immediately; retain only failures for a durable retry. */
    public void removeOrQueue(List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return;
        }
        List<String> failed = filenames.stream().filter(name -> !delete(name)).toList();
        if (failed.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    failed.forEach(LotUploadCleanup.this::queueFailure);
                }
            });
        } else {
            failed.forEach(this::queueFailure);
        }
    }
    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 60_000)
    public void retryPending() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT id, filename FROM shop_upload_cleanup "
                + "WHERE completed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY id LIMIT 100");
        } catch (RuntimeException e) {
            log.warning("[shop-upload-cleanup] unable to load cleanup queue: " + e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String filename = (String) row.get("filename");
            if (delete(filename)) {
                jdbc.update("UPDATE shop_upload_cleanup SET attempts = attempts + 1, "
                    + "last_attempt_at = CURRENT_TIMESTAMP, last_error = NULL, completed_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND completed_at IS NULL", id);
            } else {
                jdbc.update("UPDATE shop_upload_cleanup SET attempts = attempts + 1, "
                    + "last_attempt_at = CURRENT_TIMESTAMP, last_error = 'delete failed', "
                    + "next_attempt_at = CURRENT_TIMESTAMP + make_interval(mins => LEAST(1440, (attempts + 1) * 15)) "
                    + "WHERE id = ? AND completed_at IS NULL", id);
            }
        }
    }
    @Scheduled(fixedDelayString = "${savefood.lot-photo-staging.cleanup-delay-ms:300000}",
               initialDelayString = "${savefood.lot-photo-staging.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanupExpiredStagedPhotos() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT filename, shop_id, created_at, expires_at "
                + "FROM shop_lot_photo_uploads WHERE lot_id IS NULL "
                + "AND expires_at <= clock_timestamp() "
                + "AND cleanup_next_attempt_at <= clock_timestamp() "
                + "ORDER BY cleanup_next_attempt_at, expires_at, filename "
                + "LIMIT ? FOR UPDATE SKIP LOCKED", stagingProperties.getCleanupBatchSize());
        } catch (RuntimeException e) {
            log.warning("[staged-lot-photo-cleanup] unable to load stale references: " + e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            String filename = (String) row.get("filename");
            int shopId = ((Number) row.get("shop_id")).intValue();
            Object createdAt = row.get("created_at");
            Object expiresAt = row.get("expires_at");
            if (delete(filename)) {
                jdbc.update("DELETE FROM shop_lot_photo_uploads WHERE filename = ? AND shop_id = ? "
                    + "AND created_at = ? AND expires_at = ? AND lot_id IS NULL",
                    filename, shopId, createdAt, expiresAt);
            } else {
                jdbc.update("UPDATE shop_lot_photo_uploads "
                    + "SET cleanup_attempts = cleanup_attempts + 1, cleanup_last_error = 'delete failed', "
                    + "cleanup_next_attempt_at = CURRENT_TIMESTAMP + "
                    + "make_interval(mins => LEAST(1440, (cleanup_attempts + 1) * 15)) "
                    + "WHERE filename = ? AND shop_id = ? AND created_at = ? AND expires_at = ? "
                    + "AND lot_id IS NULL",
                    filename, shopId, createdAt, expiresAt);
            }
        }
    }
    private boolean delete(String filename) {
        Path path = safePath(filename);
        if (path == null) {
            return false;
        }
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private void queueFailure(String filename) {
        try {
            Runnable insert = () -> jdbc.update(
                "INSERT INTO shop_upload_cleanup (filename, last_error) VALUES (?, 'delete failed') "
                    + "ON CONFLICT (filename) DO UPDATE SET completed_at = NULL, "
                    + "next_attempt_at = CURRENT_TIMESTAMP, last_error = EXCLUDED.last_error",
                filename);
            if (cleanupTransaction == null) {
                insert.run();
            } else {
                cleanupTransaction.executeWithoutResult(ignored -> insert.run());
            }
        } catch (RuntimeException e) {
            log.severe("[shop-upload-cleanup] orphan could not be queued for retry: " + filename + ": "
                + e.getMessage());
        }
    }
    private Path safePath(String filename) {
        if (filename == null || !filename.matches(GENERATED_IMAGE)) {
            return null;
        }
        Path candidate = uploadDir.resolve(filename).normalize();
        return candidate.getParent() != null && candidate.getParent().equals(uploadDir) ? candidate : null;
    }
    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return null;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
