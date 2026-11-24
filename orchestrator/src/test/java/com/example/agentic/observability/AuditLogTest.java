package com.example.agentic.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AuditLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    @Test
    public void chainVerifiesAndContinuesAcrossReopen() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("audit.jsonl");
        try (AuditLog log = new AuditLog(f, "r1", clock::incrementAndGet)) {
            log.record("RUN_STARTED", null, "orchestrator", "start");
            log.record("STAGE_SUCCEEDED", "a", "agent-a", "ok");
        }
        try (AuditLog log = new AuditLog(f, "r1", clock::incrementAndGet)) {
            log.record("RUN_RESUMED", null, "orchestrator", "resume");
        }
        AuditLog.Verification v = AuditLog.verify(f);
        assertTrue(v.detail(), v.valid());
        assertEquals(3, v.entries());
    }

    @Test
    public void detectsModificationDeletionAndReordering() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("audit.jsonl");
        try (AuditLog log = new AuditLog(f, "r1", clock::incrementAndGet)) {
            for (int i = 0; i < 4; i++) {
                log.record("EVENT", "s" + i, "agent", "e" + i);
            }
        }
        List<String> original = Files.readAllLines(f, StandardCharsets.UTF_8);

        List<String> edited = new ArrayList<>(original);
        edited.set(1, edited.get(1).replace("\"actor\":\"agent\"", "\"actor\":\"human:mallory\""));
        Files.write(f, edited);
        assertFalse(AuditLog.verify(f).valid());

        List<String> deleted = new ArrayList<>(original);
        deleted.remove(2);
        Files.write(f, deleted);
        assertFalse(AuditLog.verify(f).valid());

        List<String> swapped = new ArrayList<>(original);
        swapped.set(1, original.get(2));
        swapped.set(2, original.get(1));
        Files.write(f, swapped);
        assertFalse(AuditLog.verify(f).valid());

        try {
            new AuditLog(f, "r1", clock::incrementAndGet).close();
            fail("must refuse to append to a broken chain");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("broken"));
        }
    }
}
