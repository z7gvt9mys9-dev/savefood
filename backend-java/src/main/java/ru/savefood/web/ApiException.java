package ru.savefood.web;

import org.springframework.http.HttpStatus;

/**
 * Mirrors FastAPI's {@code HTTPException(status_code, detail)}: carries an HTTP
 * status and a human-readable {@code detail} that the handler renders as
 * {@code {"detail": ...}}, exactly like the Python API.
 */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public ApiException(HttpStatus status, String detail) {
        this(status.value(), detail);
    }

    public int getStatus() {
        return status;
    }
}
