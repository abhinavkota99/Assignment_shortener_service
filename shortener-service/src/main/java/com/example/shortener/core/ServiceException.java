package com.example.shortener.core;

/** Domain error carrying the transport-neutral error kind; the API layer maps kinds to HTTP statuses. */
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        VALIDATION(400, "validation_failed"),
        NOT_FOUND(404, "not_found"),
        CONFLICT(409, "conflict"),
        UNAVAILABLE(503, "unavailable");

        private final int httpStatus;
        private final String code;

        Kind(int httpStatus, String code) {
            this.httpStatus = httpStatus;
            this.code = code;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String code() {
            return code;
        }
    }

    private final Kind kind;

    public ServiceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static ServiceException validation(String message) {
        return new ServiceException(Kind.VALIDATION, message);
    }

    public static ServiceException notFound(String message) {
        return new ServiceException(Kind.NOT_FOUND, message);
    }
}
