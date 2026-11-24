package com.example.shortener.store;

import com.example.shortener.core.ClickEvent;
import com.example.shortener.core.Link;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for links and click events. Kept deliberately narrow so the in-memory/journal
 * implementations used by this prototype can be swapped for a relational or key-value store.
 */
public interface LinkRepository extends AutoCloseable {

    /**
     * Atomically stores a link and, if given, its idempotency key.
     *
     * @throws DuplicateKeyException if the code or the idempotency key already exists
     */
    void insert(Link link, String idempotencyKey);

    Optional<Link> find(String code);

    Optional<Link> findByIdempotencyKey(String idempotencyKey);

    void recordClick(ClickEvent click);

    /** Snapshot of the clicks recorded for a code, oldest first. */
    List<ClickEvent> clicks(String code);

    long linkCount();

    boolean healthy();

    @Override
    default void close() {
        // nothing to release by default
    }
}
