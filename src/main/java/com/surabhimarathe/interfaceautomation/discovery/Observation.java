package com.surabhimarathe.interfaceautomation.discovery;

import java.util.List;
import java.util.UUID;

/** In-memory visible UI data, never written wholesale to evidence. */
public record Observation(UUID id, String url, String heading, List<Control> controls) {
    public Observation { controls = List.copyOf(controls); }
    public record Control(String id, String kind, String name) {}
}
