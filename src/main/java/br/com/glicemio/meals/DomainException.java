package br.com.glicemio.meals;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final HttpStatus status;

    public DomainException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus status() { return status; }

    static DomainException notFound() { return new DomainException(HttpStatus.NOT_FOUND, "Resource not found."); }
    static DomainException conflict() { return new DomainException(HttpStatus.CONFLICT, "Resource state or revision conflicts with this request."); }
    static DomainException invalid() { return new DomainException(HttpStatus.BAD_REQUEST, "Invalid request fields or timestamps."); }
}
