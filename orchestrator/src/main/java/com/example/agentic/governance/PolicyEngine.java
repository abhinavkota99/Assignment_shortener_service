package com.example.agentic.governance;

import com.example.agentic.governance.PolicyFinding.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Policy-as-code guardrails for security, compliance and change control. Pure and deterministic,
 * so every decision can be reproduced from the audit trail.
 *
 * <p>Evaluated at three points: on the plan (before any code exists), on every change set an agent
 * proposes (before it is written to the workspace) and on the whole workspace diff (before release).
 */
public final class PolicyEngine {

    /** One changed file as proposed by an agent. {@code content} is null for deletions. */
    public record FileChange(String path, String op, String content, int addedLines) { }

    static final int MAX_FILES_PER_CHANGE = 15;
    static final int MAX_ADDED_LINES_PER_CHANGE = 600;

    private static final List<String> ALLOWED_ROOTS = List.of("src/main/java/", "src/test/java/", "src/main/resources/", "docs/");
    private static final List<String> CHANGE_CONTROLLED = List.of("build.gradle", "settings.gradle", "gradle/", ".github/", "Dockerfile", "Jenkinsfile");

    private record PatternRule(String id, Severity severity, String checkpoint, Pattern pattern, String message, boolean mainOnly) { }

    private static final List<PatternRule> CONTENT_RULES = List.of(
            new PatternRule("SEC-001-hardcoded-secret", Severity.CRITICAL, null,
                    Pattern.compile("AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?i)(password|secret|api[_-]?key|token)\\s*=\\s*\"[^\"\\s]{8,}\""),
                    "hard-coded credential or private key", false),
            new PatternRule("SEC-002-command-execution", Severity.HIGH, "security-review",
                    Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|new\\s+ProcessBuilder\\s*\\("),
                    "spawns OS processes", true),
            new PatternRule("SEC-003-unsafe-deserialization", Severity.HIGH, "security-review",
                    Pattern.compile("new\\s+ObjectInputStream\\s*\\(|enableDefaultTyping|activateDefaultTyping"),
                    "unsafe deserialization", true),
            new PatternRule("SEC-004-weak-crypto-or-random", Severity.HIGH, "security-review",
                    Pattern.compile("getInstance\\(\"(MD5|SHA-?1)\"\\)|new\\s+java\\.util\\.Random\\(|new\\s+Random\\("),
                    "weak hash or non-cryptographic randomness", true),
            new PatternRule("SEC-005-tls-bypass", Severity.CRITICAL, null,
                    Pattern.compile("X509TrustManager|HostnameVerifier\\s*\\(|setAccessible\\(true\\)"),
                    "disables TLS verification or Java access checks", true),
            new PatternRule("CMP-001-personal-data", Severity.HIGH, "privacy-review",
                    Pattern.compile("(?i)\\b(clientIp|ipAddress|remoteAddr|getRemoteAddress|emailAddress|geoip)\\b"),
                    "stores or processes personal data (IP address / e-mail / geolocation)", true));

    /** Checks plan-level intent before anything is built (compliance and change control). */
    public List<PolicyFinding> evaluatePlan(JsonNode plan) {
        List<PolicyFinding> out = new ArrayList<>();
        for (JsonNode task : plan.path("tasks")) {
            String id = task.path("id").asText();
            for (JsonNode tag : task.path("tags")) {
                switch (tag.asText()) {
                    case "personal-data" -> out.add(new PolicyFinding("CMP-002-plan-personal-data", Severity.HIGH, "privacy-review",
                            "plan task " + id, "task '" + task.path("title").asText() + "' processes personal data; a privacy review is required"));
                    case "dependency-change" -> out.add(new PolicyFinding("CHG-003-plan-dependency", Severity.HIGH, "change-control",
                            "plan task " + id, "task adds or upgrades a third-party dependency"));
                    case "api-breaking" -> out.add(new PolicyFinding("CHG-004-breaking-api", Severity.HIGH, "design-review",
                            "plan task " + id, "task changes the public API contract in a non-additive way"));
                    default -> { }
                }
            }
        }
        return out;
    }

    /** Checks a proposed change set before it is applied. */
    public List<PolicyFinding> evaluateChange(List<FileChange> changes) {
        List<PolicyFinding> out = new ArrayList<>();
        int addedLines = 0;
        for (FileChange c : changes) {
            String path = c.path().replace('\\', '/');
            addedLines += c.addedLines();
            if (path.startsWith("/") || path.contains("..") || path.contains(":")) {
                out.add(new PolicyFinding("CHG-001-path-escape", Severity.CRITICAL, null, path, "path escapes the workspace"));
                continue;
            }
            if (CHANGE_CONTROLLED.stream().anyMatch(p -> path.equals(p) || path.startsWith(p) || path.endsWith("/" + p))) {
                out.add(new PolicyFinding("CHG-002-change-controlled-file", Severity.HIGH, "change-control", path,
                        "build/CI configuration is change-controlled"));
            } else if (ALLOWED_ROOTS.stream().noneMatch(path::startsWith) && !path.endsWith(".md")) {
                out.add(new PolicyFinding("CHG-005-out-of-scope-path", Severity.CRITICAL, null, path,
                        "agents may only change source, tests, resources and docs"));
            }
            if ("delete".equals(c.op()) && path.startsWith("src/test/")) {
                out.add(new PolicyFinding("QA-001-test-deleted", Severity.HIGH, "change-control", path, "deletes a test"));
            }
            if (c.content() != null) {
                boolean main = path.startsWith("src/main/");
                for (PatternRule r : CONTENT_RULES) {
                    if (r.mainOnly() && !main) {
                        continue;
                    }
                    var m = r.pattern().matcher(c.content());
                    if (m.find()) {
                        int line = (int) c.content().substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
                        out.add(new PolicyFinding(r.id(), r.severity(), r.checkpoint(), path + ":" + line, r.message()));
                    }
                }
            }
        }
        if (changes.size() > MAX_FILES_PER_CHANGE || addedLines > MAX_ADDED_LINES_PER_CHANGE) {
            out.add(new PolicyFinding("CHG-006-blast-radius", Severity.HIGH, "change-control",
                    changes.size() + " files / " + addedLines + " lines",
                    "change exceeds " + MAX_FILES_PER_CHANGE + " files or " + MAX_ADDED_LINES_PER_CHANGE + " added lines"));
        }
        return out;
    }

    public static boolean anyBlocking(List<PolicyFinding> findings) {
        return findings.stream().anyMatch(PolicyFinding::blocking);
    }

    public static String describe(List<PolicyFinding> findings) {
        return findings.isEmpty() ? "no findings" : String.join("; ", findings.stream().map(PolicyFinding::toString).toList());
    }
}
