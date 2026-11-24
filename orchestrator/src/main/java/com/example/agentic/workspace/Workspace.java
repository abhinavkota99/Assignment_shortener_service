package com.example.agentic.workspace;

import com.example.agentic.engine.Json;
import com.example.agentic.governance.PolicyEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The isolated working copy that agents change. Agents never write to the real repository: a release
 * is a reviewed patch a human applies.
 *
 * <p>Safety mechanisms:
 * <ul>
 *   <li>change sets are validated completely before the first byte is written (all-or-nothing)</li>
 *   <li>every write records the prior content in a per-stage undo log persisted on disk, so a single
 *       stage can be rolled back even while other stages run in parallel on disjoint files, and even
 *       after a process restart</li>
 *   <li>named snapshots of the whole tree give run-level rollback to a known-good checkpoint</li>
 * </ul>
 */
public final class Workspace {

    private static final Set<String> SKIP_DIRS = Set.of("build", ".gradle", "data", ".idea", ".vscode", "out");
    /** Operating-system and editor droppings that are never part of a change (e.g. macOS Finder metadata). */
    private static final Set<String> SKIP_FILES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");

    private final Path root;
    private final Path snapshots;
    private final Path undoDir;

    public Workspace(Path root, Path stateDir) {
        this.root = root.toAbsolutePath().normalize();
        this.snapshots = stateDir.resolve("snapshots");
        this.undoDir = stateDir.resolve("undo");
    }

    public Path root() {
        return root;
    }

    public void initEmpty() {
        mkdirs(root);
    }

    public void initFrom(Path source) {
        mkdirs(root);
        copyTree(source, root);
    }

    public boolean exists(String path) {
        return Files.isRegularFile(resolve(path));
    }

    public String read(String path) {
        try {
            return Files.readString(resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path + " as UTF-8 text", e);
        }
    }

    public byte[] readBytes(String path) {
        try {
            return Files.readAllBytes(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True if the file is valid UTF-8 text (binary files are diffed and hashed, never parsed). */
    public boolean isText(String path) {
        return decode(readBytes(path)) != null;
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    /** Relative paths of all files, sorted. */
    public List<String> files() {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !SKIP_FILES.contains(p.getFileName().toString()))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> SKIP_DIRS.stream().noneMatch(d -> p.startsWith(d + "/")))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Computes the effect of a change set without writing anything (for policy evaluation). */
    public List<PolicyEngine.FileChange> preview(List<FileOp> ops) {
        Map<String, String> next = plan(ops);
        List<PolicyEngine.FileChange> out = new ArrayList<>();
        for (var e : next.entrySet()) {
            String before = exists(e.getKey()) ? read(e.getKey()) : null;
            String after = e.getValue();
            out.add(new PolicyEngine.FileChange(e.getKey(), after == null ? "delete" : before == null ? "create" : "modify",
                    after, after == null ? 0 : addedLines(before, after)));
        }
        return out;
    }

    /**
     * Applies a change set atomically on behalf of {@code owner}. Returns the paths actually changed.
     * Re-application never happens implicitly: a failed attempt is undone before it is retried, and
     * re-planning restores the baseline first, so every attempt starts from a known state.
     */
    public synchronized List<String> apply(String owner, List<FileOp> ops) {
        Map<String, String> next = plan(ops); // throws ChangeConflictException before any write
        ObjectNode undo = loadUndo(owner);
        List<String> changed = new ArrayList<>();
        try {
            for (var e : next.entrySet()) {
                String path = e.getKey();
                String before = exists(path) ? read(path) : null;
                if (java.util.Objects.equals(before, e.getValue())) {
                    continue;
                }
                if (!undo.has(path)) {
                    if (before == null) {
                        undo.putNull(path);
                    } else {
                        undo.put(path, before);
                    }
                }
                saveUndo(owner, undo); // persist intent before mutating (crash-safe undo)
                write(path, e.getValue());
                changed.add(path);
            }
        } catch (RuntimeException e) {
            undo(owner); // partial application is never left behind
            throw e;
        }
        return changed;
    }

    /** Reverts every file written by {@code owner}. Returns the reverted paths. */
    public synchronized List<String> undo(String owner) {
        ObjectNode undo = loadUndo(owner);
        List<String> reverted = new ArrayList<>();
        undo.fields().forEachRemaining(e -> {
            JsonNode prior = e.getValue();
            write(e.getKey(), prior.isNull() ? null : prior.asText());
            reverted.add(e.getKey());
        });
        try {
            Files.deleteIfExists(undoFile(owner));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return reverted;
    }

    /** Drops the undo log once a stage's changes are accepted. */
    public void commit(String owner) {
        try {
            Files.deleteIfExists(undoFile(owner));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void snapshot(String label) {
        Path target = snapshots.resolve(label);
        deleteTree(target);
        mkdirs(target);
        copyTree(root, target);
    }

    public boolean hasSnapshot(String label) {
        return Files.isDirectory(snapshots.resolve(label));
    }

    /** Restores the whole tree to a snapshot and clears all undo logs (they refer to newer states). */
    public synchronized void restore(String label) {
        Path source = snapshots.resolve(label);
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("no snapshot named " + label);
        }
        deleteTree(root);
        mkdirs(root);
        copyTree(source, root);
        deleteTree(undoDir);
    }

    /** Files added, modified or deleted relative to a snapshot, with added/removed line counts. */
    public List<Map<String, Object>> diffSummary(String snapshotLabel) {
        Path base = snapshots.resolve(snapshotLabel);
        Set<String> all = new TreeSet<>(files());
        Workspace baseWs = new Workspace(base, snapshots.getParent());
        all.addAll(baseWs.files());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String p : all) {
            byte[] before = baseWs.exists(p) ? baseWs.readBytes(p) : null;
            byte[] after = exists(p) ? readBytes(p) : null;
            if (java.util.Arrays.equals(before, after)) {
                continue;
            }
            String beforeText = before == null ? null : decode(before);
            String afterText = after == null ? null : decode(after);
            boolean binary = (before != null && beforeText == null) || (after != null && afterText == null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", p);
            row.put("change", before == null ? "added" : after == null ? "deleted" : "modified");
            row.put("binary", binary);
            row.put("addedLines", binary || after == null ? 0 : addedLines(beforeText, afterText));
            row.put("removedLines", binary || before == null ? 0 : addedLines(afterText, beforeText));
            out.add(row);
        }
        return out;
    }

    // -- internals --------------------------------------------------------------------------------

    private Map<String, String> plan(List<FileOp> ops) {
        Map<String, String> next = new LinkedHashMap<>();
        for (FileOp op : ops) {
            String path = safeRelative(op.path());
            String current = next.containsKey(path) ? next.get(path) : (exists(path) ? read(path) : null);
            switch (op.op()) {
                case "create" -> {
                    if (current != null && !current.equals(op.content())) {
                        throw new ChangeConflictException("create " + path + ": file already exists with different content");
                    }
                    next.put(path, op.content());
                }
                case "patch" -> {
                    if (current == null) {
                        throw new ChangeConflictException("patch " + path + ": file does not exist");
                    }
                    int count = occurrences(current, op.find());
                    if (count == 1) {
                        next.put(path, current.replace(op.find(), op.replace()));
                    } else {
                        throw new ChangeConflictException("patch " + path + ": anchor matched " + count
                                + " times (expected exactly 1); the code may have drifted from the plan");
                    }
                }
                case "delete" -> next.put(path, null);
                default -> throw new IllegalStateException(op.op());
            }
        }
        return next;
    }

    private String safeRelative(String path) {
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root) || path.startsWith("/")) {
            throw new ChangeConflictException("path escapes workspace: " + path);
        }
        return root.relativize(p).toString().replace('\\', '/');
    }

    private Path resolve(String path) {
        return root.resolve(safeRelative(path));
    }

    private void write(String path, String content) {
        Path target = resolve(path);
        try {
            if (content == null) {
                Files.deleteIfExists(target);
            } else {
                Json.writeAtomically(target, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path undoFile(String owner) {
        return undoDir.resolve(owner.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    private ObjectNode loadUndo(String owner) {
        Path f = undoFile(owner);
        return Files.exists(f) ? (ObjectNode) Json.read(f) : Json.object();
    }

    private void saveUndo(String owner, ObjectNode undo) {
        Json.writeAtomically(undoFile(owner), Json.compact(undo));
    }

    static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /** Lines present in {@code after} but not in {@code before} (multiset difference; cheap diff stat). */
    static int addedLines(String before, String after) {
        Map<String, Integer> pool = new java.util.HashMap<>();
        if (before != null) {
            for (String l : before.split("\n", -1)) {
                pool.merge(l, 1, Integer::sum);
            }
        }
        int added = 0;
        for (String l : after.split("\n", -1)) {
            Integer n = pool.get(l);
            if (n == null || n == 0) {
                added++;
            } else {
                pool.put(l, n - 1);
            }
        }
        return added;
    }

    private static void mkdirs(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path from, Path to) {
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(from) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (SKIP_FILES.contains(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Files.copy(file, to.resolve(from.relativize(file).toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("copy " + from + " -> " + to, e);
        }
    }

    static void deleteTree(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("delete " + p, e);
        }
    }
}
