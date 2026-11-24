package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.knowledge.Catalog;
import com.example.agentic.workspace.Workspace;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brownfield code reasoning: indexes the workspace into a type-level dependency graph (who
 * references whom), test inventory, HTTP routes, and which catalog capabilities the code already
 * provides (by their declared evidence). Planning uses it to avoid rebuilding what exists; design
 * uses the reverse dependencies to find the ripple effect of a change.
 *
 * <p>Runs in parallel with requirements analysis: it needs only the code, not the requirement.
 */
public final class CodebaseAnalysisAgent implements Agent {

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern TYPE_DECL = Pattern.compile(
            "^(?:public\\s+)?(?:final\\s+|abstract\\s+)*(class|interface|record|enum)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern TEST_METHOD = Pattern.compile("@Test\\b");
    private static final Pattern ROUTE = Pattern.compile("^\\s*\\*\\s+((?:GET|POST|PUT|PATCH|DELETE|HEAD)\\b.*)$", Pattern.MULTILINE);

    @Override
    public String name() {
        return "codebase-analysis";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Workspace ws = ctx.workspace();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, ObjectNode> types = new LinkedHashMap<>();
        int testMethods = 0;
        List<String> routes = new ArrayList<>();
        for (String f : ws.files()) {
            if (!f.endsWith(".java")) {
                continue;
            }
            String src = ws.read(f);
            sources.put(f, src);
            boolean test = f.startsWith("src/test/");
            testMethods += count(TEST_METHOD, src);
            Matcher pkg = PACKAGE.matcher(src);
            Matcher decl = TYPE_DECL.matcher(src);
            if (decl.find()) {
                ObjectNode t = Json.object();
                t.put("name", decl.group(2));
                t.put("kind", decl.group(1));
                t.put("file", f);
                t.put("package", pkg.find() ? pkg.group(1) : "");
                t.put("test", test);
                types.put(decl.group(2), t);
            }
            if (!test) {
                Matcher r = ROUTE.matcher(src);
                while (r.find()) {
                    routes.add(r.group(1).trim().replaceAll("\\s{2,}", "  "));
                }
            }
        }
        // type-level references (same codebase): A references B if B's simple name occurs as a word in A
        Map<String, TreeSet<String>> dependents = new LinkedHashMap<>();
        for (ObjectNode t : types.values()) {
            String src = sources.get(t.path("file").asText());
            ArrayNode refs = t.putArray("references");
            for (String other : types.keySet()) {
                if (!other.equals(t.path("name").asText()) && Pattern.compile("\\b" + other + "\\b").matcher(src).find()) {
                    refs.add(other);
                    dependents.computeIfAbsent(other, k -> new TreeSet<>()).add(t.path("name").asText());
                }
            }
        }
        types.forEach((name, t) -> t.set("referencedBy", Json.tree(dependents.getOrDefault(name, new TreeSet<>()))));

        ArrayNode present = Json.array();
        ArrayNode evidenceLog = Json.array();
        for (Catalog.Capability c : ctx.catalog().capabilities()) {
            if (c.evidence().isEmpty()) {
                continue;
            }
            boolean all = c.evidence().stream().allMatch(e -> hasEvidence(e, types, sources));
            if (all && !types.isEmpty()) {
                present.add(c.id());
                evidenceLog.add(c.id() + " <- " + c.evidence());
            }
        }

        ObjectNode out = Json.object();
        out.put("files", ws.files().size());
        out.put("javaFiles", sources.size());
        out.put("mainTypes", types.values().stream().filter(t -> !t.path("test").asBoolean()).count());
        out.put("testMethods", testMethods);
        out.set("routes", Json.tree(routes));
        out.set("types", Json.tree(types.values()));
        out.set("presentCapabilities", present);
        out.set("capabilityEvidence", evidenceLog);
        String summary = sources.isEmpty() ? "empty workspace (greenfield)"
                : types.size() + " types, " + testMethods + " tests, " + routes.size() + " routes, capabilities present: " + present;
        return new AgentResult(summary).artifact("codebase_index", out);
    }

    /** Evidence forms: {@code type:Name} (a declared type) or {@code text:literal} (appears in main sources). */
    static boolean hasEvidence(String evidence, Map<String, ObjectNode> types, Map<String, String> sources) {
        if (evidence.startsWith("type:")) {
            ObjectNode t = types.get(evidence.substring(5));
            return t != null && !t.path("test").asBoolean();
        }
        if (evidence.startsWith("text:")) {
            String needle = evidence.substring(5);
            return sources.entrySet().stream().anyMatch(e -> e.getKey().startsWith("src/main/") && e.getValue().contains(needle));
        }
        throw new IllegalArgumentException("unknown evidence form: " + evidence);
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
