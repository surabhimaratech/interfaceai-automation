package com.surabhimarathe.interfaceautomation.discovery;

import org.springframework.core.env.Environment;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Runtime configuration, separate from portable capability declarations. */
public final class DiscoveryPolicyConfiguration {
    private DiscoveryPolicyConfiguration() {}

    public static ActionPolicy from(Environment env) {
        return new ActionPolicy(env.getRequiredProperty("discovery.allowed-origin"),
                Arrays.stream(env.getRequiredProperty("discovery.allowed-routes").split(","))
                        .map(String::trim).map(Pattern::compile).toList(),
                names(env.getRequiredProperty("discovery.allowed-actions")).stream()
                        .map(UiAction.Type::valueOf).collect(Collectors.toSet()),
                Map.of(UiAction.Type.FILL, names(env.getProperty("discovery.allowed-fill-controls", "")),
                        UiAction.Type.CLICK, names(env.getProperty("discovery.allowed-click-controls", ""))));
    }

    private static Set<String> names(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
