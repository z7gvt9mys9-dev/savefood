package ru.savefood.security;
import java.util.Objects;
import ru.savefood.web.ApiException;
/** Ownership checks, ported from auth.py {@code ensure_owner_or_admin}. */
public final class Authz {
    private Authz() {
    }
    public static void ensureOwnerOrAdmin(CurrentUser user, String role, int ownerId) {
        if (user.isAdmin()) {
            return;
        }
        ensureOwner(user, role, ownerId);
    }
    public static void ensureOwner(CurrentUser user, String role, int ownerId) {
        if (role.equals(user.role()) && Objects.equals(user.relatedId(), ownerId)) {
            return;
        }
        throw new ApiException(403, "Forbidden");
    }
}
