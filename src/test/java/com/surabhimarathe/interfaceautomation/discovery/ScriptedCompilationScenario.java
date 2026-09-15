package com.surabhimarathe.interfaceautomation.discovery;

import com.surabhimarathe.interfaceautomation.artifact.ArtifactJson;
import com.surabhimarathe.interfaceautomation.replay.*;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** Scripted local test harness only; no provider client, model messages, or authentic evidence claim. */
public final class ScriptedCompilationScenario {
    public static ReviewCheckpoint.Request request() {
        return new ReviewCheckpoint.Request("100042", "SAV-2048", new BigDecimal("25.00"), "Courtesy adjustment");
    }
    public static ActionPolicy policy(String origin) throws Exception {
        var env = new MockEnvironment();
        Properties props = new Properties();
        try (var in = ScriptedCompilationScenario.class.getResourceAsStream("/application.properties")) { props.load(in); }
        props.forEach((k,v) -> env.withProperty((String) k, (String) v));
        env.withProperty("discovery.allowed-origin", origin);
        return DiscoveryPolicyConfiguration.from(env);
    }
    public static UiAction decide(Observation o, ReviewCheckpoint.Request request) {
        return switch (o.heading()) {
            case "Member Search" -> {
                var field = control(o, "Member ID");
                yield field.filled() ? click(o, "Search") : fill(o, "Member ID", request.memberId());
            }
            case "Search Results" -> click(o, "Open");
            case "Member Details" -> {
                var selected = o.controls().stream().filter(c -> c.name().equals("Open") && c.context().contains("Savings"))
                        .findFirst().orElseGet(() -> control(o, "Open"));
                yield new UiAction(o.id(), UiAction.Type.CLICK, selected.id(), null);
            }
            case "Savings Account" -> click(o, "Prepare fee reversal");
            case "Prepare Fee Reversal" -> !control(o, "Amount").filled() ? fill(o, "Amount", request.amount().toPlainString())
                    : !control(o, "Reason").filled() ? fill(o, "Reason", request.reason()) : click(o, "Review reversal");
            case "Fee Reversal Review" -> new UiAction(o.id(), UiAction.Type.COMPLETE, "", null);
            default -> throw new IllegalStateException("UNEXPECTED_TEST_STATE");
        };
    }
    static Observation.Control control(Observation o, String name) {
        return o.controls().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
    static UiAction fill(Observation o, String name, String value) { return new UiAction(o.id(), UiAction.Type.FILL, control(o,name).id(),value); }
    static UiAction click(Observation o, String name) { return new UiAction(o.id(), UiAction.Type.CLICK, control(o,name).id(),null); }

    public static Map<String, String> isolatedRun(String origin, Path temp) throws Exception {
        UUID id = UUID.randomUUID();
        var events = new SafeEvents(temp.resolve("scripted-" + id + ".jsonl"), id);
        var policy = policy(origin);
        CompilationResult compiled;
        var request = request();
        try (var session = new BrowserSession(policy, true)) {
            session.enableCompilation(FeeReviewCapability.definition(), FeeReviewCapability.parameters(request), id, null);
            var result = new DiscoveryRunner(session, (g,o,t) -> decide(o,request), events, 20, Duration.ofSeconds(20))
                    .run(origin + "/legacy", request);
            compiled = session.compilationResult();
            if (result.code() != ActionResult.Code.CHECKPOINT_VERIFIED || compiled.artifact() == null)
                return Map.of("compile", compiled.code().name(), "replay", "NOT_RUN");
        }
        var replay = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking", policy)),
                new ReplayOptions(Duration.ofSeconds(5), Duration.ofSeconds(25),true))
                .runValidation(new ArtifactJson().write(compiled.artifact()), new InvocationParameters(FeeReviewCapability.parameters(request)));
        return Map.of("compile", compiled.code().name(), "replay", replay.status().name());
    }
}
