package com.surabhimarathe.interfaceautomation.discovery;

import java.time.Duration;

@FunctionalInterface
public interface DecisionClient {
    UiAction decide(String goal, Observation observation, Duration remaining);
    default boolean live() { return false; }
}
