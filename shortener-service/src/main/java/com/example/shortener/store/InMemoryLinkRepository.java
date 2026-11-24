package com.example.shortener.store;

import com.example.shortener.core.ClickEvent;
import com.example.shortener.core.Link;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory repository. Reads are lock-free; writes that must be atomic across maps
 * (link + idempotency key) are serialised. Subclasses get write-ahead hooks that run inside the same
 * critical section, after validation and before the in-memory state changes.
 */
public class InMemoryLinkRepository implements LinkRepository {

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final Map<String, List<ClickEvent>> clicks = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    @Override
    public void insert(Link link, String idempotencyKey) {
        synchronized (writeLock) {
            if (links.containsKey(link.code())) {
                throw new DuplicateKeyException(DuplicateKeyException.Field.CODE, link.code());
            }
            if (idempotencyKey != null && idempotencyKeys.containsKey(idempotencyKey)) {
                throw new DuplicateKeyException(DuplicateKeyException.Field.IDEMPOTENCY_KEY, idempotencyKey);
            }
            beforeInsert(link, idempotencyKey);
            applyInsert(link, idempotencyKey);
        }
    }

    @Override
    public Optional<Link> find(String code) {
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public Optional<Link> findByIdempotencyKey(String idempotencyKey) {
        String code = idempotencyKeys.get(idempotencyKey);
        return code == null ? Optional.empty() : find(code);
    }

    @Override
    public void recordClick(ClickEvent click) {
        synchronized (writeLock) {
            beforeClick(click);
            applyClick(click);
        }
    }

    @Override
    public List<ClickEvent> clicks(String code) {
        List<ClickEvent> list = clicks.get(code);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    @Override
    public long linkCount() {
        return links.size();
    }

    @Override
    public boolean healthy() {
        return true;
    }

    /** Write-ahead hook; throwing aborts the write with no in-memory change. */
    protected void beforeInsert(Link link, String idempotencyKey) {
        // no-op
    }

    /** Write-ahead hook; throwing aborts the write with no in-memory change. */
    protected void beforeClick(ClickEvent click) {
        // no-op
    }

    /** Applies state without hooks or duplicate checks (used by journal replay). */
    protected final void applyInsert(Link link, String idempotencyKey) {
        links.put(link.code(), link);
        if (idempotencyKey != null) {
            idempotencyKeys.put(idempotencyKey, link.code());
        }
    }

    protected final void applyClick(ClickEvent click) {
        List<ClickEvent> list = clicks.computeIfAbsent(click.code(), k -> new ArrayList<>());
        synchronized (list) {
            list.add(click);
        }
    }
}
