package ru.savefood.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller parameter of type {@link CurrentUser} that must be an
 * authenticated (any-role) user — the Java analogue of
 * {@code Depends(get_current_user)}. Resolution enforces: valid Bearer token
 * (else 401) and the user is not blocked (else 403). Unlike {@link Admin} it does
 * not require the admin role. Public endpoints simply omit this parameter.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auth {
}
