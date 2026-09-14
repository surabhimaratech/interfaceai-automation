package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.discovery.DiscoveryPolicyConfiguration;
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
        String json;
        try {
            if (args.length != 1 || Files.size(Path.of(args[0])) > 128_000) throw new IllegalArgumentException();
            json = Files.readString(Path.of(args[0]));
        } catch (Exception ex) {
            System.out.println(ReplayResult.failure(ReplayResult.Code.INVALID_ARTIFACT, 0));
            return;
        }
        try {
            Properties properties = new Properties();
            try (var stream = ReplayCli.class.getResourceAsStream("/application.properties")) { properties.load(stream); }
            var env = new StandardEnvironment();
            properties.setProperty("discovery.allowed-origin", System.getenv().getOrDefault("REPLAY_ORIGIN",
                    properties.getProperty("discovery.allowed-origin")));
            env.getPropertySources().addFirst(new PropertiesPropertySource("trustedReplayConfiguration", properties));
            var targets = new TargetRegistry(Map.of(properties.getProperty("replay.target-id"), DiscoveryPolicyConfiguration.from(env)));
            var options = new ReplayOptions(Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("REPLAY_STEP_TIMEOUT_MS", "5000"))),
                    Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("REPLAY_TIMEOUT_MS", "60000"))),
                    Boolean.parseBoolean(System.getenv().getOrDefault("REPLAY_HEADLESS", "false")));
            var invocation = new InvocationParameters(Map.of("memberId", System.getenv("REPLAY_MEMBER_ID"),
                    "amount", new BigDecimal(System.getenv("REPLAY_AMOUNT")), "reason", System.getenv("REPLAY_REASON")));
            result = new ReplayEngine(targets, options).run(json, invocation);
        } catch (Exception ex) { result = ReplayResult.failure(ReplayResult.Code.INVALID_PARAMETERS, 0); }
        System.out.println(result); // Only fixed metadata and the validated artifact outcome identifier; no values.
    }
}
