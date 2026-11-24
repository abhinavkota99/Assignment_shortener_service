package com.example.agentic.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WorkspaceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Workspace ws;

    @Before
    public void setUp() {
        Path state = tmp.getRoot().toPath();
        ws = new Workspace(state.resolve("ws"), state);
        ws.initEmpty();
        ws.apply("seed", List.of(FileOp.create("src/A.java", "class A {\n  int x = 1;\n}\n")));
        ws.commit("seed");
        ws.snapshot("baseline");
    }

    @Test
    public void patchMustMatchExactlyOnce() {
        List<String> changed = ws.apply("s1", List.of(FileOp.patch("src/A.java", "int x = 1;", "int x = 2;")));
        assertEquals(List.of("src/A.java"), changed);
        assertTrue(ws.read("src/A.java").contains("int x = 2;"));
        try {
            ws.apply("s2", List.of(FileOp.patch("src/A.java", "int x = 1;", "int x = 2;")));
            fail();
        } catch (ChangeConflictException e) {
            assertTrue("drift is a conflict, never a silent re-apply", e.getMessage().contains("matched 0 times"));
        }
        ws.apply("s3", List.of(FileOp.create("src/Twice.java", "a\na\n")));
        try {
            ws.apply("s3", List.of(FileOp.patch("src/Twice.java", "a", "b")));
            fail();
        } catch (ChangeConflictException e) {
            assertTrue(e.getMessage().contains("matched 2 times"));
        }
    }

    @Test
    public void changeSetsAreAllOrNothing() {
        try {
            ws.apply("s1", List.of(FileOp.create("src/B.java", "class B {}"), FileOp.patch("src/A.java", "nope", "x")));
            fail();
        } catch (ChangeConflictException expected) {
            // validated before any write
        }
        assertFalse(ws.exists("src/B.java"));
    }

    @Test
    public void undoRevertsOnlyThatStage() {
        ws.apply("s1", List.of(FileOp.patch("src/A.java", "int x = 1;", "int x = 9;")));
        ws.apply("s2", List.of(FileOp.create("src/C.java", "class C {}")));
        assertEquals(List.of("src/A.java"), ws.undo("s1"));
        assertTrue(ws.read("src/A.java").contains("int x = 1;"));
        assertTrue("other stage untouched", ws.exists("src/C.java"));
        ws.undo("s2");
        assertFalse(ws.exists("src/C.java"));
    }

    @Test
    public void snapshotRestoreAndDiff() {
        ws.apply("s1", List.of(FileOp.create("src/D.java", "class D {}"), FileOp.patch("src/A.java", "int x = 1;", "int x = 3;")));
        var diff = ws.diffSummary("baseline");
        assertEquals(2, diff.size());
        assertEquals("modified", diff.get(0).get("change"));
        assertEquals("added", diff.get(1).get("change"));
        ws.restore("baseline");
        assertFalse(ws.exists("src/D.java"));
        assertTrue(ws.diffSummary("baseline").isEmpty());
    }

    @Test
    public void pathEscapeIsRejected() {
        try {
            ws.apply("evil", List.of(FileOp.create("../outside.txt", "x")));
            fail();
        } catch (ChangeConflictException e) {
            assertTrue(e.getMessage().contains("escapes"));
        }
    }

    @Test
    public void createConflictsWithDifferentExistingContent() {
        try {
            ws.apply("s1", List.of(FileOp.create("src/A.java", "something else")));
            fail();
        } catch (ChangeConflictException e) {
            assertTrue(e.getMessage().contains("already exists"));
        }
    }
}
