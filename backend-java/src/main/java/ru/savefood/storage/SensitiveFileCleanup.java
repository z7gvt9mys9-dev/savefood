package ru.savefood.storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Durable, exact-reference deletion for private KYC, delivery-proof, and receipt files. */
@Service
public class SensitiveFileCleanup {
    public enum Storage {
        NEEDY_KYC("needy_kyc", "/needy_uploads/"),
        VOLUNTEER_KYC("volunteer_kyc", "/volunteer_kyc/"),
        DELIVERY_PHOTO("delivery_photo", "/delivery_photos/"),
        LEGACY_DELIVERY_PHOTO("legacy_delivery_photo", "/volunteer_uploads/"),
        RECEIPT("receipt", "/receipts/");
        private final String databaseValue;
        private final String referencePrefix;
        Storage(String databaseValue, String referencePrefix) {
            this.databaseValue = databaseValue;
            this.referencePrefix = referencePrefix;
        }
        static Storage fromDatabase(String value) {
            for (Storage storage : values()) {
                if (storage.databaseValue.equals(value)) {
                    return storage;
                }
            }
            return null;
        }
    }
    @FunctionalInterface
    interface FileDeleter {
        void deleteIfExists(Path path) throws Exception;
    }
    private static final Logger log = Logger.getLogger(SensitiveFileCleanup.class.getName());
    private final JdbcTemplate jdbc;
    private final Map<Storage, Path> roots;
    private final FileDeleter deleter;
    private final TransactionTemplate cleanupTransaction;
    @Autowired
    public SensitiveFileCleanup(JdbcTemplate jdbc,
            @Value("${savefood.needy-upload-dir:../backend/needy/uploads}") String needyDir,
            @Value("${savefood.volunteer-kyc-upload-dir:../backend/volunteer/kyc_uploads}") String volunteerKycDir,
            @Value("${savefood.delivery-photo-upload-dir:../backend/volunteer/delivery_photos}") String deliveryPhotoDir,
            @Value("${savefood.volunteer-upload-dir:../backend/volunteer/uploads}") String legacyDeliveryPhotoDir,
            @Value("${savefood.receipt-upload-dir:../backend/shop/receipt_uploads}") String receiptDir,
            PlatformTransactionManager transactionManager) {
        this(jdbc, needyDir, volunteerKycDir, deliveryPhotoDir, legacyDeliveryPhotoDir, receiptDir,
            Files::deleteIfExists, requiresNew(transactionManager));
    }
    public SensitiveFileCleanup(JdbcTemplate jdbc, String needyDir, String volunteerKycDir,
            String deliveryPhotoDir, String legacyDeliveryPhotoDir) {
        this(jdbc, needyDir, volunteerKycDir, deliveryPhotoDir, legacyDeliveryPhotoDir,
            legacyDeliveryPhotoDir,
            Files::deleteIfExists, null);
    }
    public SensitiveFileCleanup(JdbcTemplate jdbc, String needyDir, String volunteerKycDir,
            String deliveryPhotoDir, String legacyDeliveryPhotoDir, String receiptDir) {
        this(jdbc, needyDir, volunteerKycDir, deliveryPhotoDir, legacyDeliveryPhotoDir, receiptDir,
            Files::deleteIfExists, null);
    }
    public SensitiveFileCleanup(JdbcTemplate jdbc, String needyDir, String volunteerKycDir,
            String deliveryPhotoDir, String legacyDeliveryPhotoDir,
            PlatformTransactionManager transactionManager) {
        this(jdbc, needyDir, volunteerKycDir, deliveryPhotoDir, legacyDeliveryPhotoDir,
            legacyDeliveryPhotoDir,
            Files::deleteIfExists, requiresNew(transactionManager));
    }
    SensitiveFileCleanup(JdbcTemplate jdbc, String needyDir, String volunteerKycDir,
            String deliveryPhotoDir, String legacyDeliveryPhotoDir, FileDeleter deleter) {
        this(jdbc, needyDir, volunteerKycDir, deliveryPhotoDir, legacyDeliveryPhotoDir,
            legacyDeliveryPhotoDir, deleter, null);
    }
    SensitiveFileCleanup(JdbcTemplate jdbc, String needyDir, String volunteerKycDir,
            String deliveryPhotoDir, String legacyDeliveryPhotoDir, String receiptDir, FileDeleter deleter,
            TransactionTemplate cleanupTransaction) {
        this.jdbc = jdbc;
        this.roots = Map.of(
            Storage.NEEDY_KYC, root(needyDir),
            Storage.VOLUNTEER_KYC, root(volunteerKycDir),
            Storage.DELIVERY_PHOTO, root(deliveryPhotoDir),
            Storage.LEGACY_DELIVERY_PHOTO, root(legacyDeliveryPhotoDir),
            Storage.RECEIPT, root(receiptDir));
        this.deleter = deleter;
        this.cleanupTransaction = cleanupTransaction;
    }
    /** Retains an exact, newly-created sensitive file only if the transaction commits. */
    public boolean deleteOnRollback(Storage storage, String fileRef) {
        requireSafePath(storage, fileRef);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Transaction synchronization is not active");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteOrQueueNow(storage, fileRef);
                }
            }
        });
        return true;
    }
    /** Own a new file until its database reference has committed. */
    public CleanupGuard deleteUnlessPersisted(Storage storage, String fileRef) {
        requireSafePath(storage, fileRef);
        boolean transactional = TransactionSynchronizationManager.isActualTransactionActive();
        CleanupGuard guard = new CleanupGuard(storage, fileRef, transactional);
        if (transactional) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("Transaction synchronization is not active");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED || !guard.persisted) {
                        deleteOrQueueNow(storage, fileRef);
                    }
                }
            });
        }
        return guard;
    }
    public final class CleanupGuard implements AutoCloseable {
        private final Storage storage;
        private final String fileRef;
        private final boolean transactional;
        private volatile boolean persisted;
        private boolean closed;
        private CleanupGuard(Storage storage, String fileRef, boolean transactional) {
            this.storage = storage;
            this.fileRef = fileRef;
            this.transactional = transactional;
        }
        /** Marks the file as receipt-owned, subject to transaction commit. */
        public void persisted() {
            persisted = true;
        }
        @Override
        public void close() {
            if (!closed && !transactional && !persisted) {
                closed = true;
                deleteOrQueue(storage, fileRef);
            }
        }
    }
    public void trackAndDeleteAfterCommit(Storage storage, String fileRef) {
        requireSafePath(storage, fileRef);
        Long id = jdbc.queryForObject(
            "INSERT INTO sensitive_file_cleanup (storage_type, file_ref) VALUES (?, ?) "
                + "ON CONFLICT (storage_type, file_ref) DO UPDATE SET completed_at = NULL, "
                + "next_attempt_at = CURRENT_TIMESTAMP, last_error = NULL RETURNING id",
            Long.class, storage.databaseValue, fileRef);
        if (id == null) {
            throw new IllegalStateException("Sensitive-file cleanup was not persisted");
        }
        afterCommit(() -> process(id, storage, fileRef));
    }
    /** Delete an otherwise unreferenced file now, durably queueing only failures. */
    public void deleteOrQueue(Storage storage, String fileRef) {
        Path path = requireSafePath(storage, fileRef);
        try {
            deleter.deleteIfExists(path);
        } catch (Exception e) {
            Runnable queue = () -> queueFailure(storage, fileRef, message(e));
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        queue.run();
                    }
                });
            } else {
                queue.run();
            }
        }
    }
    private void deleteOrQueueNow(Storage storage, String fileRef) {
        Path path = requireSafePath(storage, fileRef);
        try {
            deleter.deleteIfExists(path);
        } catch (Exception e) {
            queueFailure(storage, fileRef, message(e));
        }
    }
    @EventListener(ApplicationReadyEvent.class)
    public void afterStartup() {
        retryPending();
    }
    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 60_000)
    public void retryPending() {
        inventoryLegacyNeedyKyc();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                "SELECT id, storage_type, file_ref FROM sensitive_file_cleanup "
                    + "WHERE completed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP "
                    + "ORDER BY id LIMIT 100");
        } catch (RuntimeException e) {
            log.warning("[sensitive-file-cleanup] unable to load queue: " + message(e));
            return;
        }
        for (Map<String, Object> row : rows) {
            Storage storage = Storage.fromDatabase((String) row.get("storage_type"));
            long id = ((Number) row.get("id")).longValue();
            String fileRef = (String) row.get("file_ref");
            process(id, storage, fileRef);
        }
    }
    private void process(long id, Storage storage, String fileRef) {
        try {
            deleter.deleteIfExists(requireSafePath(storage, fileRef));
            jdbc.update(
                "UPDATE sensitive_file_cleanup SET attempts = attempts + 1, "
                    + "last_attempt_at = CURRENT_TIMESTAMP, last_error = NULL, "
                    + "completed_at = CURRENT_TIMESTAMP WHERE id = ? AND completed_at IS NULL",
                id);
        } catch (Exception e) {
            jdbc.update(
                "UPDATE sensitive_file_cleanup SET attempts = attempts + 1, "
                    + "last_attempt_at = CURRENT_TIMESTAMP, last_error = ?, "
                    + "next_attempt_at = CURRENT_TIMESTAMP + "
                    + "make_interval(mins => LEAST(1440, (attempts + 1) * 15)) "
                    + "WHERE id = ? AND completed_at IS NULL",
                truncate(message(e)), id);
            log.warning("[sensitive-file-cleanup] retained for retry: " + fileRef);
        }
    }
    private void queueFailure(Storage storage, String fileRef, String error) {
        try {
            Runnable insert = () -> jdbc.update(
                "INSERT INTO sensitive_file_cleanup (storage_type, file_ref, last_error) VALUES (?, ?, ?) "
                    + "ON CONFLICT (storage_type, file_ref) DO UPDATE SET completed_at = NULL, "
                    + "next_attempt_at = CURRENT_TIMESTAMP, last_error = EXCLUDED.last_error",
                storage.databaseValue, fileRef, truncate(error));
            if (cleanupTransaction == null) {
                insert.run();
            } else {
                cleanupTransaction.executeWithoutResult(ignored -> insert.run());
            }
        } catch (RuntimeException e) {
            log.severe("[sensitive-file-cleanup] failed file could not be queued: "
                + fileRef + ": " + message(e));
            throw e;
        }
    }
    private void inventoryLegacyNeedyKyc() {
        Path needyRoot = roots.get(Storage.NEEDY_KYC);
        try (var files = Files.list(needyRoot)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String fileRef = Storage.NEEDY_KYC.referencePrefix + path.getFileName();
                try {
                    jdbc.update(
                        "INSERT INTO sensitive_file_cleanup (needy_id, storage_type, file_ref) "
                            + "VALUES (NULL, ?, ?) ON CONFLICT (storage_type, file_ref) DO NOTHING",
                        Storage.NEEDY_KYC.databaseValue, fileRef);
                } catch (RuntimeException e) {
                    log.warning("[sensitive-file-cleanup] unable to inventory legacy KYC file: "
                        + path.getFileName());
                }
            });
        } catch (Exception e) {
            if (Files.exists(needyRoot)) {
                log.warning("[sensitive-file-cleanup] unable to inventory legacy KYC directory: "
                    + message(e));
            }
        }
    }
    private Path requireSafePath(Storage storage, String fileRef) {
        if (storage == null || fileRef == null || !fileRef.startsWith(storage.referencePrefix)) {
            throw new IllegalArgumentException("Invalid sensitive-file reference");
        }
        String filename = fileRef.substring(storage.referencePrefix.length());
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("Invalid sensitive-file reference");
        }
        Path root = roots.get(storage);
        Path candidate = root.resolve(filename).normalize();
        if (candidate.getParent() == null || !candidate.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid sensitive-file reference");
        }
        return candidate;
    }
    private static void afterCommit(Runnable cleanup) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }
    private static Path root(String value) {
        return Paths.get(value).toAbsolutePath().normalize();
    }
    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    private static String truncate(String value) {
        return value.substring(0, Math.min(1000, value.length()));
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
