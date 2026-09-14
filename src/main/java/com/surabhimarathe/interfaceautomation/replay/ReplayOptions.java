package com.surabhimarathe.interfaceautomation.replay;

import java.time.Duration;

public record ReplayOptions(Duration stepTimeout, Duration overallTimeout, boolean headless) {
    public ReplayOptions {
        if (stepTimeout == null || overallTimeout == null
                || stepTimeout.compareTo(Duration.ofMillis(1)) < 0 || stepTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || overallTimeout.compareTo(Duration.ofMillis(1)) < 0 || overallTimeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("INVALID_REPLAY_TIMEOUT");
    }
    public static ReplayOptions defaults() { return new ReplayOptions(Duration.ofSeconds(5), Duration.ofSeconds(60), false); }
}
