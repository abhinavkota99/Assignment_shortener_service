package com.example.shortener.store;

/** A unique constraint (short code or idempotency key) would be violated. */
public class DuplicateKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Field { CODE, IDEMPOTENCY_KEY }

    private final Field field;

    public DuplicateKeyException(Field field, String value) {
        super(field + " already exists: " + value);
        this.field = field;
    }

    public Field field() {
        return field;
    }
}
