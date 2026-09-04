package ru.savefood.web;
import org.springframework.http.HttpStatus;
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
