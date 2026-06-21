package kz.savefood.web;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link ApiException} (and bean-validation failures) as FastAPI-style
 * {@code {"detail": ...}} JSON with the carried status. The {@code /admin} surface
 * writes its auth denials directly in {@link kz.savefood.security.AdminAuthFilter}
 * (it runs before MVC); this advice covers everything thrown from controller
 * bodies and argument resolvers across all modules.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus());
        if (ex.getStatus() == 401) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return builder.body(Map.of("detail", ex.getMessage()));
    }

    /** Malformed request body → 422, the FastAPI default for validation errors. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .orElse("Validation error");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("detail", detail));
    }
}
