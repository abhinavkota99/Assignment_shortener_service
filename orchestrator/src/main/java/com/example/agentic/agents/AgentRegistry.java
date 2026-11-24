package com.example.agentic.agents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Name -> agent lookup used by the orchestrator to bind workflow stages to implementations. */
public final class AgentRegistry {

    /** Built-in no-op agent used by fan-out join barriers. */
    public static final String JOIN = "join";

    private final Map<String, Agent> agents = new LinkedHashMap<>();

    public AgentRegistry register(Agent agent) {
        if (agents.putIfAbsent(agent.name(), agent) != null) {
            throw new IllegalArgumentException("duplicate agent " + agent.name());
        }
        return this;
    }

    public Optional<Agent> find(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    public Agent get(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("no agent registered as '" + name + "'"));
    }

    public static AgentRegistry standard() {
        return new AgentRegistry()
                .register(new SimpleAgent(JOIN, ctx -> new AgentResult("synchronisation barrier reached")))
                .register(new RequirementsAgent())
                .register(new LlmRequirementsAgent())
                .register(new CodebaseAnalysisAgent())
                .register(new PlanningAgent())
                .register(new DesignAgent())
                .register(new ImplementationAgent())
                .register(new BuildTestAgent())
                .register(new SecurityScanAgent())
                .register(new DocsAgent())
                .register(new ReleaseReadinessAgent())
                .register(new ReleaseAgent());
    }

    /** Adapter for small agents defined inline (and for tests). */
    public record SimpleAgent(String name, Body body) implements Agent {

        @FunctionalInterface
        public interface Body {
            AgentResult run(AgentContext ctx) throws AgentException;
        }

        @Override
        public AgentResult execute(AgentContext ctx) throws AgentException {
            return body.run(ctx);
        }
    }
}
