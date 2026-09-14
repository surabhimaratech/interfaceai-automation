package com.surabhimarathe.interfaceautomation.discovery;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Opt-in CLI flow; returned details stay in memory and out of evidence logs. */
@Component
@ConditionalOnProperty(name = "discovery.flow", havingValue = "true")
public final class DiscoveryFlow implements ApplicationRunner {
    private final Environment env;
    private DiscoveryRunner.Result result;
    private CompilationResult compilation;
    public CompilationResult compilationResult() { return compilation; }
    private final HandoffCoordinator handoff = new HandoffCoordinator();
    public HandoffCoordinator handoff() { return handoff; }
    public DiscoveryFlow(Environment env) { this.env = env; }
    public DiscoveryRunner.Result result() { return result; }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if (env.getProperty("discovery.proof", Boolean.class, false)) throw new IllegalArgumentException("SELECT_ONE_DISCOVERY_MODE");
        boolean compile = env.getProperty("discovery.compile", Boolean.class, false);
        boolean persist = env.getProperty("discovery.persist-artifact", Boolean.class, false);
        if (persist && !compile) throw new IllegalArgumentException("PERSISTENCE_REQUIRES_COMPILATION");
        UUID runId = UUID.randomUUID();
        Path evidence = Path.of(env.getProperty("discovery.evidence", "evidence/flow-{runId}.jsonl")
                .replace("{runId}", runId.toString()));
        if (compile && !evidence.getFileName().toString().contains(runId.toString()))
            throw new IllegalArgumentException("COMPILATION_REQUIRES_CORRELATED_EVIDENCE_FILENAME");
        Path output = persist ? Path.of(env.getRequiredProperty("discovery.artifact-directory")) : null;
        var client = new OpenRouterClient(System.getenv("OPENROUTER_API_KEY"));
        String origin = env.getRequiredProperty("discovery.allowed-origin");
        var policy = DiscoveryPolicyConfiguration.from(env);
        var request = new ReviewCheckpoint.Request(env.getProperty("discovery.member-id", "100042"),
            env.getProperty("discovery.account-id", "SAV-2048"), new BigDecimal(env.getProperty("discovery.amount", "25.00")),
            env.getProperty("discovery.reason", "Courtesy adjustment"));
        var events = new SafeEvents(evidence, runId);
        handoff.attach(events);
        try (var session = new BrowserSession(policy)) {
            if (compile) session.enableCompilation(FeeReviewCapability.definition(), FeeReviewCapability.parameters(request), runId, output);
            result = new DiscoveryRunner(session, client, events,
                env.getProperty("discovery.max-steps", Integer.class, 20),
                Duration.ofSeconds(env.getProperty("discovery.timeout-seconds", Long.class, 120L)))
                .handoff(handoff, Duration.ofSeconds(env.getProperty("discovery.handoff-timeout-seconds", Long.class, 180L)),
                    env.getProperty("discovery.simulate-expiry", Boolean.class, false),
                    env.getProperty("discovery.progress-limit", Integer.class, 3))
                .run(origin + "/legacy", request);
            if (compile) {
                compilation = session.compilationResult();
                System.out.println(compilation);
            }
            // Typed review details are returned by the runner and retained in result(), never logged.
            System.out.println("Discovery result: " + result.state() + " / " + result.code() + " / steps=" + result.steps());
        } finally {
            handoff.close();
            events.record(RunState.CLOSED, null, null, null, false);
        }
    }
}
