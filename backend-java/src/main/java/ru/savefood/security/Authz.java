package ru.savefood.security;

import java.util.Objects;
import ru.savefood.web.ApiException;

/** Ownership checks, ported from auth.py {@code ensure_owner_or_admin}. */
public final class Authz {
    private Authz() {
    }

    /**
     * Allow the request only for an admin, or for a user of {@code role} whose
     * token is bound (via {@code related_id}) to {@code ownerId}. Throws 403
     * otherwise.
     */
    public static void ensureOwnerOrAdmin(CurrentUser user, String role, int ownerId) {
        if (user.isAdmin()) {
            return;
        }
        if (role.equals(user.role()) && Objects.equals(user.relatedId(), ownerId)) {
            return;
        }
        throw new ApiException(403, "Forbidden");
    }
}
