package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.discovery.DiscoveryPolicyConfiguration;
import com.surabhimarathe.interfaceautomation.approval.*;
import java.time.Clock;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/** Local fixture invocation only. Never prints inputs, outputs, paths, or exception details. */
public final class ReplayCli {
    private ReplayCli() {}
    public static void main(String[] args) {
        ReplayResult result;
        byte[] json;
        try {
            if (args.length != 1 || Files.size(Path.of(args[0])) > 128_000) throw new IllegalArgumentException();
            json = Files.readAllBytes(Path.of(args[0]));
        } catch (Exception ex) {
            System.out.println(ReplayResult.failure(ReplayResult.Code.INVALID_ARTIFACT, 0));
            return;
        }
        try {
            ReplayMode mode = ReplayMode.valueOf(System.getenv("REPLAY_MODE"));
            ReplayGovernance governance = null;
            String governanceDirectory = System.getenv("REPLAY_GOVERNANCE_DIRECTORY");
            if (governanceDirectory != null && !governanceDirectory.isBlank()) {
                try {
                    governance = new ReplayGovernance(new ApprovalService(new FileGovernanceStore(Path.of(governanceDirectory)),
                            ApprovalPolicy.defaults(),Clock.systemUTC()));
                } catch (RuntimeException ex) { throw new ApprovalException(ApprovalException.Code.STORAGE_FAILURE); }
            }
            Properties properties = new Properties();
            try (var stream = ReplayCli.class.getResourceAsStream("/application.properties")) { properties.load(stream); }
            var env = new StandardEnvironment();
            properties.setProperty("discovery.allowed-origin", System.getenv().getOrDefault("REPLAY_ORIGIN",
                    properties.getProperty("discovery.allowed-origin")));
            env.getPropertySources().addFirst(new PropertiesPropertySource("trustedReplayConfiguration", properties));
            String targetId = properties.getProperty("replay.target-id");
            String expiryName = System.getenv("REPLAY_SESSION_EXPIRY_NAME");
            var markers = expiryName == null ? Map.<String,SessionExpiryMarker>of() : Map.of(targetId,
                    new SessionExpiryMarker(com.surabhimarathe.interfaceautomation.artifact.LocatorSpec.Role.valueOf(
                            System.getenv().getOrDefault("REPLAY_SESSION_EXPIRY_ROLE","ALERT")),expiryName));
            var targets = TargetRegistry.singleTenant(new TenantId(System.getenv("REPLAY_TENANT_ID")),
                    Map.of(targetId, DiscoveryPolicyConfiguration.from(env)),markers);
            var options = new ReplayOptions(Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("REPLAY_STEP_TIMEOUT_MS", "5000"))),
                    Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("REPLAY_TIMEOUT_MS", "60000"))),
                    Boolean.parseBoolean(System.getenv().getOrDefault("REPLAY_HEADLESS", "false")));
            var invocation = new InvocationParameters(Map.of("memberId", System.getenv("REPLAY_MEMBER_ID"),
                    "amount", new BigDecimal(System.getenv("REPLAY_AMOUNT")), "reason", System.getenv("REPLAY_REASON")));
            // Host environment only. Disabled unless an explicit trusted directory is provided.
            String diagnosticDirectory = System.getenv("REPLAY_DIAGNOSTIC_DIRECTORY");
            DiagnosticStore store = diagnosticDirectory == null || diagnosticDirectory.isBlank()
                    ? null : new DiagnosticStore(Path.of(diagnosticDirectory));
            if (Boolean.parseBoolean(System.getenv().getOrDefault("REPLAY_HANDOFF","false"))) {
                if (markers.isEmpty() || options.headless()) throw new IllegalArgumentException("INVALID_HANDOFF_CONFIGURATION");
                try (var handoff = new ReplayHandoff(Duration.ofMillis(Long.parseLong(
                            System.getenv().getOrDefault("REPLAY_HANDOFF_TIMEOUT_MS","60000"))),
                            Integer.parseInt(System.getenv().getOrDefault("REPLAY_HANDOFF_LIMIT","2")));
                     var operator = new ReplayOperatorServer(handoff,Integer.parseInt(
                            System.getenv().getOrDefault("REPLAY_OPERATOR_PORT","18082")))) {
                    result = new ReplayEngine(targets, options, store, handoff,governance).run(json,invocation,mode);
                }
            } else result = new ReplayEngine(targets, options, store,null,governance).run(json, invocation,mode);
        } catch (ApprovalException ex) { result = ReplayResult.failure(ReplayResult.Code.GOVERNANCE_FAILURE,0);
        } catch (Exception ex) { result = ReplayResult.failure(ReplayResult.Code.INVALID_PARAMETERS, 0); }
        System.out.println(result); // Only fixed metadata and the validated artifact outcome identifier; no values.
    }
}
