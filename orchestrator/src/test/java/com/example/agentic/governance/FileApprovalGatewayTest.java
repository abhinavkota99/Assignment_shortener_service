package com.example.agentic.governance;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileApprovalGatewayTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Approval.Request req(String stage, String checkpoint) {
        return new Approval.Request("r", stage, checkpoint, "s", List.of(), List.of());
    }

    @Test
    public void exactKeyWinsOverWildcardAndRejectsAreHonoured() throws Exception {
        Path f = tmp.newFile("a.json").toPath();
        Files.writeString(f, "{\"decisions\":["
                + "{\"checkpoint\":\"*/privacy-review\",\"decision\":\"reject\",\"approver\":\"dpo\",\"comment\":\"no\"},"
                + "{\"checkpoint\":\"implement:T-api/privacy-review\",\"decision\":\"approve\",\"approver\":\"dpo\",\"comment\":\"ok\"}]}");
        FileApprovalGateway g = new FileApprovalGateway(f);
        assertEquals(Approval.Outcome.APPROVED, g.decide(req("implement:T-api", "privacy-review")).outcome());
        Approval.Decision other = g.decide(req("plan", "privacy-review"));
        assertEquals(Approval.Outcome.REJECTED, other.outcome());
        assertEquals("dpo", other.approver());
        assertEquals(Approval.Outcome.PENDING, g.decide(req("release", "release")).outcome());
    }

    @Test
    public void agentsAndAnonymousCannotApprove() throws Exception {
        Path f = tmp.newFile("b.json").toPath();
        Files.writeString(f, "{\"decisions\":[{\"checkpoint\":\"release/release\",\"decision\":\"approve\",\"approver\":\"Agent\"},"
                + "{\"checkpoint\":\"design/design-review\",\"decision\":\"approve\",\"approver\":\" \"}]}");
        FileApprovalGateway g = new FileApprovalGateway(f);
        assertEquals(Approval.Outcome.PENDING, g.decide(req("release", "release")).outcome());
        assertEquals(Approval.Outcome.PENDING, g.decide(req("design", "design-review")).outcome());
    }

    @Test
    public void missingFileMeansPendingNeverApproved() {
        assertEquals(Approval.Outcome.PENDING, new FileApprovalGateway(null).decide(req("release", "release")).outcome());
        assertEquals(Approval.Outcome.PENDING,
                new FileApprovalGateway(tmp.getRoot().toPath().resolve("nope.json")).decide(req("release", "release")).outcome());
    }
}
