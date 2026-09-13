package com.surabhimarathe.interfaceautomation.discovery;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Opt-in CLI flow; returned details stay in memory and out of evidence logs. */
@Component
@ConditionalOnProperty(name = "discovery.flow", havingValue = "true")
public final class DiscoveryFlow implements ApplicationRunner {
    private final Environment env;
    private DiscoveryRunner.Result result;
    public DiscoveryFlow(Environment env) { this.env = env; }
    public DiscoveryRunner.Result result() { return result; }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if (env.getProperty("discovery.proof", Boolean.class, false)) throw new IllegalArgumentException("SELECT_ONE_DISCOVERY_MODE");
        var client = new OpenRouterClient(System.getenv("OPENROUTER_API_KEY"));
        String origin = env.getRequiredProperty("discovery.allowed-origin");
        var policy = new ActionPolicy(origin,
            Arrays.stream(env.getRequiredProperty("discovery.allowed-routes").split(",")).map(Pattern::compile).toList(),
            Set.copyOf(Arrays.stream(env.getRequiredProperty("discovery.allowed-actions").split(",")).map(String::trim).map(UiAction.Type::valueOf).toList()));
        var request = new ReviewCheckpoint.Request(env.getProperty("discovery.member-id", "100042"),
            env.getProperty("discovery.account-id", "SAV-2048"), new BigDecimal(env.getProperty("discovery.amount", "25.00")),
            env.getProperty("discovery.reason", "Courtesy adjustment"));
        var events = new SafeEvents(Path.of(env.getProperty("discovery.evidence", "evidence/flow-" + UUID.randomUUID() + ".jsonl")));
        try (var session = new BrowserSession(policy)) {
            result = new DiscoveryRunner(session, client, events,
                env.getProperty("discovery.max-steps", Integer.class, 20),
                Duration.ofSeconds(env.getProperty("discovery.timeout-seconds", Long.class, 120L)))
                .run(origin + "/legacy", request);
            // Typed review details are returned by the runner and retained in result(), never logged.
            System.out.println("Discovery result: " + result.state() + " / " + result.code() + " / steps=" + result.steps());
        } finally {
            events.record(RunState.CLOSED, null, null, null, false);
        }
    }
}
