package com.example.agentic.governance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.agentic.engine.Json;
import com.example.agentic.governance.PolicyFinding.Severity;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PolicyEngineTest {

    private final PolicyEngine policy = new PolicyEngine();

    private static PolicyEngine.FileChange file(String path, String content) {
        return new PolicyEngine.FileChange(path, "create", content, content.split("\n").length);
    }

    private static boolean has(List<PolicyFinding> fs, String rulePrefix, Severity severity) {
        return fs.stream().anyMatch(f -> f.ruleId().startsWith(rulePrefix) && f.severity() == severity);
    }

    @Test
    public void cleanChangeHasNoFindings() {
        assertTrue(policy.evaluateChange(List.of(file("src/main/java/A.java", "class A { int x = 1; }"))).isEmpty());
    }

    @Test
    public void secretsAndTlsBypassAreCritical() {
        List<PolicyFinding> f = policy.evaluateChange(List.of(
                file("src/main/java/A.java", "class A {\n String apiKey = \"sk_live_1234567890abcdef\";\n}"),
                file("src/main/java/B.java", "class B implements X509TrustManager {}")));
        assertTrue(has(f, "SEC-001", Severity.CRITICAL));
        assertTrue(has(f, "SEC-005", Severity.CRITICAL));
        assertTrue(PolicyEngine.anyBlocking(f));
        assertTrue(f.get(0).location().endsWith(":2"));
    }

    @Test
    public void riskyApisNeedSecurityReviewInMainCodeOnly() {
        List<PolicyFinding> main = policy.evaluateChange(List.of(file("src/main/java/A.java", "new ProcessBuilder(\"sh\")")));
        assertTrue(has(main, "SEC-002", Severity.HIGH));
        assertEquals("security-review", main.get(0).checkpoint());
        assertTrue(policy.evaluateChange(List.of(file("src/test/java/ATest.java", "new ProcessBuilder(\"sh\")"))).isEmpty());
        assertTrue(has(policy.evaluateChange(List.of(file("src/main/java/R.java", "new Random(42)"))), "SEC-004", Severity.HIGH));
        assertFalse(has(policy.evaluateChange(List.of(file("src/main/java/R.java", "new SecureRandom()"))), "SEC-004", Severity.HIGH));
    }

    @Test
    public void personalDataNeedsPrivacyReview() {
        List<PolicyFinding> f = policy.evaluateChange(List.of(file("src/main/java/C.java", "String clientIp = ex.getRemoteAddress();")));
        assertTrue(has(f, "CMP-001", Severity.HIGH));
        assertEquals("privacy-review", f.get(0).checkpoint());
    }

    @Test
    public void changeControlAndScope() {
        assertTrue(has(policy.evaluateChange(List.of(file("build.gradle", "x"))), "CHG-002", Severity.HIGH));
        assertTrue(has(policy.evaluateChange(List.of(file("../etc/passwd", "x"))), "CHG-001", Severity.CRITICAL));
        assertTrue(has(policy.evaluateChange(List.of(file("scripts/deploy.sh", "x"))), "CHG-005", Severity.CRITICAL));
        assertTrue(has(policy.evaluateChange(List.of(new PolicyEngine.FileChange("src/test/java/ATest.java", "delete", null, 0))),
                "QA-001", Severity.HIGH));
        assertTrue(policy.evaluateChange(List.of(file("docs/notes.md", "fine"))).isEmpty());
    }

    @Test
    public void blastRadiusNeedsChangeControl() {
        List<PolicyEngine.FileChange> many = new ArrayList<>();
        for (int i = 0; i <= PolicyEngine.MAX_FILES_PER_CHANGE; i++) {
            many.add(file("src/main/java/F" + i + ".java", "class F" + i + " {}"));
        }
        assertTrue(has(policy.evaluateChange(many), "CHG-006", Severity.HIGH));
    }

    @Test
    public void planLevelComplianceFindings() throws Exception {
        var plan = Json.MAPPER.readTree("{\"tasks\":[{\"id\":\"T-geo\",\"title\":\"geo\",\"tags\":[\"personal-data\"]},"
                + "{\"id\":\"T-x\",\"title\":\"x\",\"tags\":[\"dependency-change\"]},{\"id\":\"T-y\",\"title\":\"y\"}]}");
        List<PolicyFinding> f = policy.evaluatePlan(plan);
        assertEquals(2, f.size());
        assertEquals("privacy-review", f.get(0).checkpoint());
        assertTrue(f.get(0).location().contains("T-geo"));
        assertEquals("change-control", f.get(1).checkpoint());
    }
}
