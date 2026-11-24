package com.example.agentic.cli;

import com.example.agentic.agents.AgentRegistry;
import com.example.agentic.engine.FaultInjector;
import com.example.agentic.engine.Json;
import com.example.agentic.engine.Orchestrator;
import com.example.agentic.engine.RunState;
import com.example.agentic.governance.Approval;
import com.example.agentic.governance.ConsoleApprovalGateway;
import com.example.agentic.governance.FileApprovalGateway;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.knowledge.Catalog;
import com.example.agentic.knowledge.Scenario;
import com.example.agentic.llm.AnthropicLlmClient;
import com.example.agentic.llm.LlmClient;
import com.example.agentic.observability.AuditLog;
import com.example.agentic.observability.Metrics;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line entry point.
 *
 * <pre>
 *   agentic run &lt;scenario-dir&gt; [--approvals f.json] [--interactive] [--fault spec]... [--run-id id] [--llm]
 *   agentic resume &lt;run-id&gt; [--answers f.json] [--approvals f.json] [--interactive] [--recover]
 *   agentic status &lt;run-id&gt;
 *   agentic verify-audit &lt;run-id&gt;
 *   agentic metrics
 *   agentic stop &lt;run-id&gt;           (engages the kill switch of a running run)
 * </pre>
 */
public final class AgenticCli {

    private AgenticCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream out) {
        if (args.length == 0) {
            usage(out);
            return 2;
        }
        Path repo = Path.of(System.getProperty("agentic.repo", ".")).toAbsolutePath().normalize();
        Path runs = repo.resolve(System.getProperty("agentic.runs", "runs"));
        Opts o = Opts.parse(args);
        try {
            switch (args[0]) {
                case "run" -> {
                    Scenario scenario = Scenario.load(repo.resolve(o.positional(1)));
                    String runId = o.runId != null ? o.runId
                            : scenario.id() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                    out.println("== run " + runId + " : " + scenario.title());
                    RunState st = orchestrator(repo, runs, o, out).start(scenario, runId);
                    return summary(st, runs, out);
                }
                case "resume" -> {
                    String runId = o.positional(1);
                    out.println("== resume " + runId);
                    RunState st = orchestrator(repo, runs, o, out).resume(runId, o.answers == null ? null : repo.resolve(o.answers), o.recover);
                    return summary(st, runs, out);
                }
                case "status" -> {
                    RunState st = RunState.load(RunState.dirOf(runs, o.positional(1)));
                    out.println(st.runId() + " " + st.status() + (st.stopReason() == null ? "" : " - " + st.stopReason()));
                    st.stages().forEach((id, r) -> out.printf("  %-30s %-18s attempts=%d%n", id, r.status(), r.attempts()));
                    return 0;
                }
                case "verify-audit" -> {
                    AuditLog.Verification v = AuditLog.verify(RunState.dirOf(runs, o.positional(1)).resolve("audit.jsonl"));
                    out.println((v.valid() ? "VALID" : "INVALID") + ": " + v.entries() + " entries, " + v.detail());
                    return v.valid() ? 0 : 1;
                }
                case "metrics" -> {
                    out.println(Json.pretty(Metrics.aggregate(runs)));
                    return 0;
                }
                case "stop" -> {
                    Path stop = RunState.dirOf(runs, o.positional(1)).resolve("STOP");
                    Files.writeString(stop, "stop requested at " + java.time.Instant.now() + "\n");
                    out.println("kill switch engaged: " + stop);
                    return 0;
                }
                default -> {
                    usage(out);
                    return 2;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.println("error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            out.println("error: " + e);
            return 1;
        }
    }

    private static Orchestrator orchestrator(Path repo, Path runs, Opts o, PrintStream out) {
        Catalog catalog = Catalog.load(repo.resolve("knowledge/catalog.json"));
        Approval.Gateway gateway = new FileApprovalGateway(o.approvals == null ? null : repo.resolve(o.approvals));
        if (o.interactive) {
            gateway = new ConsoleApprovalGateway(gateway, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                    out, System.getProperty("user.name", "operator"));
        }
        LlmClient llm = o.llm ? AnthropicLlmClient.fromEnv() : null;
        if (o.llm && llm == null) {
            out.println("warning: --llm given but ANTHROPIC_API_KEY is not set; using deterministic agents");
        }
        return new Orchestrator(repo, runs, catalog, AgentRegistry.standard(), new PolicyEngine(), gateway, llm,
                new FaultInjector(o.faults), Orchestrator.Options.defaults(), System::currentTimeMillis, out);
    }

    private static int summary(RunState st, Path runs, PrintStream out) {
        Path dir = runs.resolve(st.runId());
        out.println();
        out.println("status : " + st.status() + (st.stopReason() == null ? "" : " - " + st.stopReason()));
        out.println("report : " + dir.resolve("REPORT.md"));
        if (Files.exists(dir.resolve("CLARIFICATIONS.md")) && st.status().name().equals("AWAITING_INPUT")) {
            out.println("next   : answer " + dir.resolve("CLARIFICATIONS.md") + " then: ./agentic resume " + st.runId() + " --answers <file>");
        }
        if (st.status().name().equals("AWAITING_APPROVAL")) {
            out.println("next   : review " + dir.resolve("APPROVALS-PENDING.md") + " then: ./agentic resume " + st.runId()
                    + " --approvals <file>");
        }
        if (Files.exists(dir.resolve("release/CHANGE.patch"))) {
            out.println("release: " + dir.resolve("release"));
        }
        return switch (st.status()) {
            case SUCCEEDED -> 0;
            case AWAITING_INPUT, AWAITING_APPROVAL -> 3;
            default -> 1;
        };
    }

    private static void usage(PrintStream out) {
        out.println("""
                usage:
                  agentic run <scenario-dir> [--approvals <file>] [--interactive] [--fault <stage>:<mode>[:n]]... [--run-id <id>] [--llm]
                  agentic resume <run-id> [--answers <file>] [--approvals <file>] [--interactive] [--recover]
                  agentic status <run-id>
                  agentic verify-audit <run-id>
                  agentic metrics
                  agentic stop <run-id>
                fault modes: transient (retryable error n times), crash (primary agent fails), corrupt (break a changed file)""");
    }

    /** Minimal option parsing (no dependency on a CLI library). */
    static final class Opts {
        final List<String> positional = new ArrayList<>();
        final List<String> faults = new ArrayList<>();
        String approvals;
        String answers;
        String runId;
        boolean interactive;
        boolean llm;
        boolean recover;

        static Opts parse(String[] args) {
            Opts o = new Opts();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--approvals" -> o.approvals = value(args, ++i);
                    case "--answers" -> o.answers = value(args, ++i);
                    case "--run-id" -> o.runId = value(args, ++i);
                    case "--fault" -> o.faults.add(value(args, ++i));
                    case "--interactive" -> o.interactive = true;
                    case "--llm" -> o.llm = true;
                    case "--recover" -> o.recover = true;
                    default -> {
                        if (args[i].startsWith("--")) {
                            throw new IllegalArgumentException("unknown option " + args[i]);
                        }
                        o.positional.add(args[i]);
                    }
                }
            }
            return o;
        }

        String positional(int index) {
            if (positional.size() <= index) {
                throw new IllegalArgumentException("missing argument; run without arguments for usage");
            }
            return positional.get(index);
        }

        private static String value(String[] args, int i) {
            if (i >= args.length) {
                throw new IllegalArgumentException("missing value for " + args[i - 1]);
            }
            return args[i];
        }
    }
}
