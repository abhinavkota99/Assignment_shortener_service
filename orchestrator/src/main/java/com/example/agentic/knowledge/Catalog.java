package com.example.agentic.knowledge;

import com.example.agentic.engine.Json;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engineering knowledge base agents reason with ({@code knowledge/catalog.json}): known product
 * capabilities and how to recognise them in requirements text and in code, the implementation tasks
 * that deliver them (with dependencies, file ownership and recipes), and known ambiguity patterns.
 *
 * <p>Keeping this declarative is a deliberate governance choice: what agents may build, and how,
 * is reviewable data under change control, not behaviour hidden in prompts.
 */
public final class Catalog {

    public record Capability(String id, String title, List<String> keywords, List<String> requirements,
                             List<String> acceptance, List<String> evidence, List<String> tasks, List<String> api,
                             List<String> data, List<String> risks) {
        public Capability {
            keywords = nz(keywords);
            requirements = nz(requirements);
            acceptance = nz(acceptance);
            evidence = nz(evidence);
            tasks = nz(tasks);
            api = nz(api);
            data = nz(data);
            risks = nz(risks);
        }
    }

    /** {@code type}: "reference" (copy {@code paths} from the reference implementation), "changeset" ({@code file}), or "none". */
    public record Recipe(String type, List<String> paths, String file) {
        public Recipe {
            paths = nz(paths);
        }
    }

    public record Task(String id, String title, String capability, List<String> dependsOn, List<String> files,
                       List<String> tests, Recipe recipe, List<String> tags) {
        public Task {
            dependsOn = nz(dependsOn);
            files = nz(files);
            tests = nz(tests);
            tags = nz(tags);
            recipe = recipe == null ? new Recipe("none", List.of(), null) : recipe;
        }
    }

    public record Option(String id, String label, List<String> capabilities) {
        public Option {
            capabilities = nz(capabilities);
        }
    }

    public record AmbiguityRule(String id, List<String> triggers, String question, boolean blocking, List<Option> options,
                                String defaultOption) {
        public AmbiguityRule {
            triggers = nz(triggers);
            options = nz(options);
        }
    }

    /** A vague quality word ("fast", "scalable") that needs a measurable target. */
    public record Qualifier(String term, String category, String question, String assumption) { }

    record Data(List<Capability> capabilities, List<Task> tasks, List<AmbiguityRule> ambiguities, List<Qualifier> qualifiers) { }

    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<AmbiguityRule> ambiguities;
    private final List<Qualifier> qualifiers;
    private final Path baseDir;

    private Catalog(Data data, Path baseDir) {
        this.baseDir = baseDir;
        nz(data.capabilities()).forEach(c -> capabilities.put(c.id(), c));
        nz(data.tasks()).forEach(t -> tasks.put(t.id(), t));
        this.ambiguities = nz(data.ambiguities());
        this.qualifiers = nz(data.qualifiers());
        validate();
    }

    public static Catalog load(Path file) {
        return new Catalog(Json.read(file, Data.class), file.toAbsolutePath().getParent());
    }

    private void validate() {
        for (Capability c : capabilities.values()) {
            for (String t : c.tasks()) {
                if (!tasks.containsKey(t)) {
                    throw new IllegalStateException("capability " + c.id() + " references unknown task " + t);
                }
            }
        }
        for (Task t : tasks.values()) {
            if (!capabilities.containsKey(t.capability())) {
                throw new IllegalStateException("task " + t.id() + " references unknown capability " + t.capability());
            }
            for (String d : t.dependsOn()) {
                if (!tasks.containsKey(d)) {
                    throw new IllegalStateException("task " + t.id() + " depends on unknown task " + d);
                }
            }
        }
        for (AmbiguityRule a : ambiguities) {
            for (Option o : a.options()) {
                o.capabilities().forEach(this::capability);
            }
        }
    }

    public List<Capability> capabilities() {
        return List.copyOf(capabilities.values());
    }

    public Capability capability(String id) {
        return Optional.ofNullable(capabilities.get(id)).orElseThrow(() -> new IllegalArgumentException("unknown capability " + id));
    }

    public Task task(String id) {
        return Optional.ofNullable(tasks.get(id)).orElseThrow(() -> new IllegalArgumentException("unknown task " + id));
    }

    public List<AmbiguityRule> ambiguities() {
        return ambiguities;
    }

    public List<Qualifier> qualifiers() {
        return qualifiers;
    }

    public Path resolve(String relative) {
        return baseDir.resolve(relative).normalize();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : List.copyOf(l);
    }
}
