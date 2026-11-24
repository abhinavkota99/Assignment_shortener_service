package com.example.agentic.knowledge;

import com.example.agentic.engine.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A change request: the raw requirement as a stakeholder wrote it plus where the work starts from.
 *
 * <ul>
 *   <li>{@code greenfield}: empty workspace; {@code reference} names the module recipes copy from</li>
 *   <li>{@code brownfield}: workspace starts as a copy of {@code baseline} (existing code)</li>
 * </ul>
 */
public record Scenario(String id, String title, String type, String requirement, String baseline, String reference,
                       String workflow, Path dir) {

    public Scenario {
        Objects.requireNonNull(id, "scenario id");
        Objects.requireNonNull(requirement, "scenario requirement");
        if (!"greenfield".equals(type) && !"brownfield".equals(type)) {
            throw new IllegalArgumentException("scenario type must be greenfield or brownfield");
        }
        if ("brownfield".equals(type) && baseline == null) {
            throw new IllegalArgumentException("brownfield scenario needs a baseline");
        }
        workflow = workflow == null ? "/workflows/sdlc.json" : workflow;
    }

    record Data(String id, String title, String type, String requirement, String baseline, String reference, String workflow) { }

    public static Scenario load(Path dir) {
        Path file = dir.resolve("scenario.json");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("no scenario.json in " + dir);
        }
        Data d = Json.read(file, Data.class);
        return new Scenario(d.id(), d.title(), d.type(), d.requirement(), d.baseline(), d.reference(), d.workflow(),
                dir.toAbsolutePath().normalize());
    }

    public boolean greenfield() {
        return "greenfield".equals(type);
    }
}
