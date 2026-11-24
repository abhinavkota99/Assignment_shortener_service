package com.example.agentic.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.agentic.knowledge.Catalog;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class LlmRequirementsAgentTest {

    private final Catalog catalog = Catalog.load(Path.of("knowledge/catalog.json").toAbsolutePath());

    @Test
    public void acceptsValidProposalEvenWithSurroundingProse() throws Exception {
        LlmRequirementsAgent.Proposal p = LlmRequirementsAgent.parse(
                "Here you go:\n{\"capabilities\":[\"link_expiration\",\"link_expiration\"],\"ambiguities\":[\"AMB-safety\"],\"rationale\":\"r\"}",
                catalog);
        assertEquals(List.of("link_expiration"), p.capabilities());
        assertEquals(List.of("AMB-safety"), p.ambiguities());
    }

    @Test
    public void rejectsAnythingUnexpected() {
        for (String reply : new String[] {
                null, "no json here", "{not json}", "{\"capabilities\":\"link_expiration\",\"ambiguities\":[]}",
                "{\"capabilities\":[\"rm -rf\"],\"ambiguities\":[]}", "{\"capabilities\":[],\"ambiguities\":[]}"}) {
            try {
                LlmRequirementsAgent.parse(reply, catalog);
                fail("accepted: " + reply);
            } catch (AgentException e) {
                assertEquals(AgentException.Kind.PERMANENT, e.kind());
            }
        }
    }

    @Test
    public void keywordMatchingRespectsWordStarts() {
        assertTrue(RequirementsAgent.containsWord("we want custom aliases", "alias"));
        assertFalse(RequirementsAgent.containsWord("a dealias step", "alias"));
        assertFalse(RequirementsAgent.containsWord("an accurate rate", "rate limit"));
        assertTrue(RequirementsAgent.containsWord("links should expire", "expire"));
    }
}
