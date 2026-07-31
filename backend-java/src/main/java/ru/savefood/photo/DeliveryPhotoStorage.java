package ru.savefood.photo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deletes private delivery proofs only after their database reference committed.
 *
 * <p>A proof is deliberately kept outside the public nginx tree.  The deferred
 * deletion matters for route teardown: if a route transaction rolls back, the
 * ticket must keep both its reference and its file.
 */
@Service
public class DeliveryPhotoStorage {

    private final String privateDir;
    private final String legacyDir;

    public DeliveryPhotoStorage(
            @Value("${savefood.delivery-photo-upload-dir:../backend/volunteer/delivery_photos}") String privateDir,
            @Value("${savefood.volunteer-upload-dir:../backend/volunteer/uploads}") String legacyDir) {
        this.privateDir = privateDir;
        this.legacyDir = legacyDir;
    }

    /** Queue deletion after commit, or delete immediately outside a transaction. */
    public void deleteAfterCommit(String photoRef) {
        Path path = pathFor(photoRef);
        if (path == null) {
            return;
        }
        Runnable cleanup = () -> {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // The DB no longer exposes this file; a later retention sweep can
                // remove a rare filesystem orphan without affecting correctness.
            }
        };
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

    private Path pathFor(String photoRef) {
        if (photoRef == null || photoRef.isBlank()) {
            return null;
        }
        String dir;
        String filename;
        if (photoRef.startsWith("/delivery_photos/")) {
            dir = privateDir;
            filename = photoRef.substring("/delivery_photos/".length());
        } else if (photoRef.startsWith("/volunteer_uploads/")) {
            // Legacy proofs can still be scrubbed while old rows are migrated.
            dir = legacyDir;
            filename = photoRef.substring("/volunteer_uploads/".length());
        } else {
            return null;
        }
        // Stored references are virtual URL paths with exactly one generated
        // filename. Refuse separators rather than normalising a path traversal
        // into some unrelated file in the private directory.
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\")) {
            return null;
        }
        try {
            Path base = Paths.get(dir).toAbsolutePath().normalize();
            Path candidate = base.resolve(filename).normalize();
            return candidate.getParent() != null && candidate.getParent().equals(base) ? candidate : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
