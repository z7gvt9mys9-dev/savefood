package ru.savefood.photo;
import ru.savefood.storage.SensitiveFileCleanup;
import ru.savefood.storage.SensitiveFileCleanup.Storage;
import org.springframework.stereotype.Service;
@Service
public class DeliveryPhotoStorage {
    private final SensitiveFileCleanup cleanup;
    public DeliveryPhotoStorage(SensitiveFileCleanup cleanup) {
        this.cleanup = cleanup;
    }
    /** Persist cleanup before commit, then attempt deletion after commit. */
    public void deleteAfterCommit(String photoRef) {
        Storage storage = storageFor(photoRef);
        if (storage != null) {
            cleanup.trackAndDeleteAfterCommit(storage, photoRef);
        }
    }
    /** Clean a file that never acquired a database reference. */
    public void deleteOrQueue(String photoRef) {
        Storage storage = storageFor(photoRef);
        if (storage != null) {
            cleanup.deleteOrQueue(storage, photoRef);
        }
    }
    private Storage storageFor(String photoRef) {
        if (photoRef == null || photoRef.isBlank()) {
            return null;
        }
        if (photoRef.startsWith("/delivery_photos/")) {
            return Storage.DELIVERY_PHOTO;
        }
        if (photoRef.startsWith("/volunteer_uploads/")) {
            return Storage.LEGACY_DELIVERY_PHOTO;
        }
        return null;
    }
}
