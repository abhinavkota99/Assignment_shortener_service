package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.governance.PolicyFinding;
import com.example.agentic.workspace.ChangeConflictException;
import com.example.agentic.workspace.FileOp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements one plan task as a change set against the workspace.
 *
 * <p>Change sets come from the task's recipe: {@code reference} copies reviewed files from the
 * reference implementation (greenfield), {@code changeset} applies reviewed edits (brownfield). A task
 * with no approved recipe, or whose recipe no longer applies, fails here and the workflow hands it to
 * {@link LlmImplementationAgent} when a model is configured. Whatever the source, the change set goes
 * through the same pipeline: preview, policy evaluation, atomic application with undo log.
 */
public final class ImplementationAgent implements Agent {

    @Override
    public String name() {
        return "implementation";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        String taskId = ctx.stage().taskId();
        JsonNode plan = ctx.input("plan");
        ctx.input("design"); // lineage: implementation follows this design version
        JsonNode task = null;
        for (JsonNode t : plan.path("tasks")) {
            if (t.path("id").asText().equals(taskId)) {
                task = t;
            }
        }
        if (task == null) {
            throw AgentException.permanent("task " + taskId + " is not in the current plan");
        }
        List<FileOp> ops = loadRecipe(ctx, task);
        List<PolicyEngine.FileChange> preview;
        try {
            preview = ctx.workspace().preview(ops);
        } catch (ChangeConflictException e) {
            throw AgentException.permanent("change set for " + taskId + " does not apply: " + e.getMessage());
        }
        List<PolicyFinding> findings = ctx.policy().evaluateChange(preview);
        if (PolicyEngine.anyBlocking(findings)) {
            return new AgentResult("blocked by policy before writing").findings(findings);
        }
        List<String> changed;
        try {
            changed = ctx.workspace().apply(ctx.stage().id(), ops);
        } catch (ChangeConflictException e) {
            throw AgentException.permanent("change set for " + taskId + " does not apply: " + e.getMessage());
        }
        ObjectNode out = Json.object();
        out.put("task", taskId);
        out.put("recipe", task.path("recipe").path("type").asText());
        out.set("changedFiles", Json.tree(changed));
        out.put("operations", ops.size());
        int added = preview.stream().mapToInt(PolicyEngine.FileChange::addedLines).sum();
        out.put("addedLines", added);
        return new AgentResult(task.path("title").asText() + ": " + changed.size() + " files, +" + added + " lines")
                .artifact("impl." + taskId, out).findings(findings).changed(changed);
    }

    static List<FileOp> loadRecipe(AgentContext ctx, JsonNode task) throws AgentException {
        JsonNode recipe = task.path("recipe");
        String type = recipe.path("type").asText();
        List<FileOp> ops = new ArrayList<>();
        switch (type) {
            case "reference" -> {
                String ref = ctx.scenario().reference();
                if (ref == null) {
                    throw AgentException.permanent("scenario has no reference implementation for task " + task.path("id").asText());
                }
                Path base = ctx.repoRoot().resolve(ref).normalize();
                for (JsonNode p : recipe.path("paths")) {
                    Path src = base.resolve(p.asText()).normalize();
                    if (!src.startsWith(base)) {
                        throw AgentException.permanent("recipe path escapes reference: " + p.asText());
                    }
                    try {
                        ops.add(FileOp.create(p.asText(), Files.readString(src, StandardCharsets.UTF_8)));
                    } catch (IOException e) {
                        throw AgentException.permanent("cannot read reference file " + p.asText() + ": " + e.getMessage());
                    }
                }
            }
            case "changeset" -> {
                Path file = ctx.catalog().resolve(recipe.path("file").asText());
                if (!Files.exists(file)) {
                    throw AgentException.permanent("change set not found: " + file);
                }
                Path dir = file.getParent();
                for (JsonNode op : Json.read(file).path("ops")) {
                    String content = text(op, "content");
                    if (op.hasNonNull("contentFile")) { // new files are kept as real source files for reviewability
                        Path src = dir.resolve(op.get("contentFile").asText()).normalize();
                        if (!src.startsWith(dir)) {
                            throw AgentException.permanent("contentFile escapes recipe directory: " + src);
                        }
                        try {
                            content = Files.readString(src, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw AgentException.permanent("cannot read recipe content " + src + ": " + e.getMessage());
                        }
                    }
                    ops.add(new FileOp(op.path("op").asText(), op.path("path").asText(), content, text(op, "find"),
                            text(op, "replace")));
                }
            }
            default -> throw AgentException.permanent("task " + task.path("id").asText() + " has no approved recipe ('" + type
                    + "'): it needs a human implementation or the model-backed fallback (--llm)");
        }
        if (ops.isEmpty()) {
            throw AgentException.permanent("empty change set for task " + task.path("id").asText());
        }
        return ops;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }
}
