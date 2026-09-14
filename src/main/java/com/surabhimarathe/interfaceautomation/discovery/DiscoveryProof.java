package com.surabhimarathe.interfaceautomation.discovery;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.*;

/** Opt-in one-action proof, deliberately not a full discovery loop or replay engine. */
@Component
@ConditionalOnProperty(name = "discovery.proof", havingValue = "true")
public final class DiscoveryProof implements ApplicationRunner {
    private final Environment env;
    public DiscoveryProof(Environment env) { this.env = env; }
    @Override public void run(ApplicationArguments args) throws Exception {
        // Fail before opening a browser or creating evidence if credentials are absent.
        var client = new OpenRouterClient(System.getenv("OPENROUTER_API_KEY"));
        String origin = env.getRequiredProperty("discovery.allowed-origin");
        var policy = DiscoveryPolicyConfiguration.from(env);
        UUID runId = UUID.randomUUID();
        var events = new SafeEvents(Path.of(env.getProperty("discovery.evidence",
            "evidence/action-{runId}.jsonl").replace("{runId}", runId.toString())), runId);
        RunState state = RunState.CREATED;
        try (var session = new BrowserSession(policy)) {
            state = RunState.OBSERVING;
            events.record(state, null, null, null, false);
            var observation = session.open(origin + "/legacy");
            state = RunState.DECIDING;
            events.record(state, null, null, OpenRouterClient.MODEL, false);
            var action = client.decide(env.getRequiredProperty("discovery.goal"), observation);
            state = RunState.ACTING;
            events.record(state, action.type(), null, OpenRouterClient.MODEL, true);
            var result = session.execute(action);
            state = switch (result.status()) {
                case SUCCEEDED -> RunState.SUCCEEDED;
                case BLOCKED -> RunState.BLOCKED;
                case FAILED -> RunState.FAILED;
            };
            events.record(state, action.type(), result.code(), OpenRouterClient.MODEL, true);
            if (state != RunState.SUCCEEDED) throw new IllegalStateException("ACTION_PROOF_NOT_SUCCESSFUL");
        } catch (Exception ex) {
            events.record(RunState.FAILED, null, null, null, false);
            // Do not include exception bodies, provider responses, goals or values.
            throw new IllegalStateException(ex instanceof OpenRouterClient.ModelFailure
                ? ex.getMessage() : "ACTION_PROOF_FAILED");
        } finally {
            events.record(RunState.CLOSED, null, null, null, false);
        }
    }
}
