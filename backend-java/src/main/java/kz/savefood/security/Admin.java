package kz.savefood.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method parameter of type {@link CurrentUser} that must be an
 * authenticated admin — the Java analogue of {@code Depends(require_admin)}.
 * Resolution enforces, in order: valid token (else 401), not blocked (else 403),
 * role == admin (else 403).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Admin {
}
