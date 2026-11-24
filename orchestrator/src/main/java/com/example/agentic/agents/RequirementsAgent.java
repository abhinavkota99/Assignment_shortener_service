package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.knowledge.Catalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises a free-text requirement into a structured, testable engineering problem:
 * capabilities with functional requirements and acceptance criteria, non-functional requirements
 * (stated or assumed), explicit assumptions, and ambiguities.
 *
 * <p>Ambiguity handling is the important part: blocking ambiguities (they change what gets built)
 * stop the run for a human answer; non-blocking ones (missing targets) are resolved with a documented
 * default so work can proceed, and the assumption is surfaced in the report for review.
 */
public final class RequirementsAgent implements Agent {

    private static final Pattern MEASURABLE = Pattern.compile(
            "[^.;]*\\b\\d+(\\.\\d+)?\\s*(ms|milliseconds|seconds|s|rps|requests per second|%|percent|req/s)\\b[^.;]*",
            Pattern.CASE_INSENSITIVE);

    static final List<String> STANDARD_NFRS = List.of(
            "Every input from clients is validated; errors use a consistent JSON error format.",
            "No secrets, credentials or personal data are written to source code or logs.",
            "Every change ships with automated tests; the existing test suite must keep passing.",
            "Changes are backward compatible for existing clients and stored data unless explicitly approved.");

    @Override
    public String name() {
        return "requirements-rules";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        String text = ctx.scenario().requirement();
        Map<String, String> matched = new LinkedHashMap<>();
        for (Catalog.Capability c : ctx.catalog().capabilities()) {
            firstMatch(text, c.keywords()).ifPresent(k -> matched.put(c.id(), k));
        }
        List<String> triggered = new ArrayList<>();
        for (Catalog.AmbiguityRule a : ctx.catalog().ambiguities()) {
            if (firstMatch(text, a.triggers()).isPresent()) {
                triggered.add(a.id());
            }
        }
        return build(ctx, matched, triggered, "rules");
    }

    /**
     * Builds the requirements artifact from a capability interpretation (from keyword rules or a
     * validated model proposal) and the ambiguity rules that fired.
     */
    static AgentResult build(AgentContext ctx, Map<String, String> matchedCapabilities, List<String> triggeredAmbiguities,
                             String interpreter) {
        Catalog catalog = ctx.catalog();
        String text = ctx.scenario().requirement();
        JsonNode answers = ctx.optionalInput("human.clarifications").map(n -> n.path("answers")).orElse(Json.object());
        String answeredBy = ctx.optionalInput("human.clarifications").map(n -> n.path("answeredBy").asText()).orElse(null);

        ObjectNode out = Json.object();
        out.put("source", text);
        out.put("interpreter", interpreter);
        List<AgentResult.DecisionDraft> decisions = new ArrayList<>();

        Map<String, String> capabilitySource = new LinkedHashMap<>();
        matchedCapabilities.forEach((id, kw) -> capabilitySource.put(id, "stated (matched '" + kw + "')"));

        ArrayNode ambiguities = out.putArray("ambiguities");
        ArrayNode assumptions = out.putArray("assumptions");
        ArrayNode outOfScope = out.putArray("outOfScope");
        int openBlocking = 0;
        for (String ambId : triggeredAmbiguities) {
            Catalog.AmbiguityRule rule = catalog.ambiguities().stream().filter(a -> a.id().equals(ambId)).findFirst().orElseThrow();
            ObjectNode a = ambiguities.addObject();
            a.put("id", rule.id());
            a.put("trigger", firstMatch(text, rule.triggers()).orElse(""));
            a.put("question", rule.question());
            a.put("blocking", rule.blocking());
            a.put("defaultOption", rule.defaultOption());
            a.set("options", Json.tree(rule.options()));
            String answer = answers.path(rule.id()).asText(null);
            Optional<Catalog.Option> chosen = rule.options().stream().filter(o -> o.id().equals(answer)).findFirst();
            if (answer != null && chosen.isEmpty()) {
                a.put("invalidAnswer", answer);
            }
            if (chosen.isPresent()) {
                ObjectNode res = a.putObject("resolution");
                res.put("option", chosen.get().id());
                res.put("source", "human");
                res.put("by", answeredBy);
            } else if (!rule.blocking()) {
                chosen = rule.options().stream().filter(o -> o.id().equals(rule.defaultOption())).findFirst();
                ObjectNode res = a.putObject("resolution");
                res.put("option", rule.defaultOption());
                res.put("source", "assumed-default");
                assumptions.add(rule.question() + " -> assumed '" + rule.defaultOption() + "' (non-blocking; review)");
            } else {
                openBlocking++;
            }
            if (chosen.isPresent()) {
                String chosenId = chosen.get().id();
                chosen.get().capabilities().forEach(c -> capabilitySource.putIfAbsent(c, "clarification " + rule.id() + "=" + chosenId));
                rule.options().stream().filter(o -> !o.id().equals(chosenId))
                        .forEach(o -> outOfScope.add(o.label() + " (not chosen for " + rule.id() + ")"));
                decisions.add(new AgentResult.DecisionDraft("Resolved " + rule.id() + " as '" + chosenId + "'",
                        a.path("resolution").path("source").asText().equals("human")
                                ? "answered by " + answeredBy : "non-blocking ambiguity; documented default applied",
                        rule.options().stream().map(Catalog.Option::id).filter(id -> !id.equals(chosenId)).toList()));
            }
        }

        ArrayNode caps = out.putArray("capabilities");
        int fr = 0;
        for (var e : capabilitySource.entrySet()) {
            Catalog.Capability c = catalog.capability(e.getKey());
            ObjectNode n = caps.addObject();
            n.put("id", c.id());
            n.put("title", c.title());
            n.put("source", e.getValue());
            ArrayNode reqs = n.putArray("requirements");
            for (String r : c.requirements()) {
                ObjectNode rn = reqs.addObject();
                rn.put("id", "FR-" + (++fr));
                rn.put("text", r);
            }
            n.set("acceptance", Json.tree(c.acceptance()));
        }

        ArrayNode nfrs = out.putArray("nonFunctional");
        int nfrId = 0;
        Matcher m = MEASURABLE.matcher(text);
        Set<String> stated = new LinkedHashSet<>();
        while (m.find()) {
            stated.add(m.group().trim());
        }
        for (String s : stated) {
            nfr(nfrs, ++nfrId, s, "stated");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Catalog.Qualifier q : catalog.qualifiers()) {
            if (containsWord(lower, q.term()) && stated.stream().noneMatch(s -> s.toLowerCase(Locale.ROOT).contains(q.term()))) {
                nfr(nfrs, ++nfrId, q.assumption(), "assumed: '" + q.term() + "' has no measurable target");
                assumptions.add("'" + q.term() + "' interpreted as: " + q.assumption());
                decisions.add(new AgentResult.DecisionDraft("Interpreted vague quality '" + q.term() + "' as a measurable target",
                        q.assumption(), List.of()));
            }
        }
        for (String s : STANDARD_NFRS) {
            nfr(nfrs, ++nfrId, s, "engineering standard");
        }

        out.put("openBlockingAmbiguities", openBlocking);
        out.put("summary", capabilitySource.size() + " capabilities, " + fr + " functional requirements, "
                + nfrs.size() + " NFRs, " + ambiguities.size() + " ambiguities (" + openBlocking + " blocking & open)");
        AgentResult result = new AgentResult(out.path("summary").asText()).artifact("requirements", out);
        result.decision("Interpreted requirement as capabilities " + capabilitySource.keySet(),
                "interpreter=" + interpreter + "; " + capabilitySource, List.of());
        decisions.forEach(d -> result.decision(d.summary(), d.rationale(), d.alternatives()));
        return result;
    }

    private static void nfr(ArrayNode nfrs, int id, String text, String source) {
        ObjectNode n = nfrs.addObject();
        n.put("id", "NFR-" + id);
        n.put("text", text);
        n.put("source", source);
    }

    static Optional<String> firstMatch(String text, List<String> phrases) {
        String lower = text.toLowerCase(Locale.ROOT);
        return phrases.stream().filter(p -> containsWord(lower, p.toLowerCase(Locale.ROOT))).findFirst();
    }

    /** Phrase match at a word start (so "alias" matches "aliases" but "rate" does not match "accurate"). */
    static boolean containsWord(String lowerText, String phrase) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(phrase)).matcher(lowerText).find();
    }
}
