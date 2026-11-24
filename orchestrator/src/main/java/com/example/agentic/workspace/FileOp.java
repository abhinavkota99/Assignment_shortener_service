package com.example.agentic.workspace;

import java.util.Objects;

/**
 * One edit in a change set.
 * <ul>
 *   <li>{@code create}: new file with {@code content} (no-op if identical content exists; conflict otherwise)</li>
 *   <li>{@code patch}: replace the single occurrence of {@code find} with {@code replace}</li>
 *   <li>{@code delete}: remove the file</li>
 * </ul>
 * Patches must match exactly once, so an agent can never silently edit the wrong place and code that
 * drifted since the change set was reviewed is detected as a conflict instead of being patched blindly.
 */
public record FileOp(String op, String path, String content, String find, String replace) {

    public FileOp {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(path, "path");
        switch (op) {
            case "create" -> Objects.requireNonNull(content, "content for create " + path);
            case "patch" -> {
                Objects.requireNonNull(find, "find for patch " + path);
                Objects.requireNonNull(replace, "replace for patch " + path);
                if (find.isEmpty()) {
                    throw new IllegalArgumentException("empty find for patch " + path);
                }
            }
            case "delete" -> { }
            default -> throw new IllegalArgumentException("unknown op '" + op + "' for " + path);
        }
    }

    public static FileOp create(String path, String content) {
        return new FileOp("create", path, content, null, null);
    }

    public static FileOp patch(String path, String find, String replace) {
        return new FileOp("patch", path, null, find, replace);
    }
}
