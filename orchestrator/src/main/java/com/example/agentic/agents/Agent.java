package com.example.agentic.agents;

/**
 * A unit of autonomous work. Agents read upstream artifacts and the workspace through
 * {@link AgentContext} and return proposals in an {@link AgentResult}; the orchestrator, not the
 * agent, decides whether a result is accepted (gates, policy, human approval). Agents must be
 * idempotent: they can be retried, re-executed after re-planning, or rolled back at any time.
 */
public interface Agent {

    String name();

    AgentResult execute(AgentContext ctx) throws AgentException;
}
