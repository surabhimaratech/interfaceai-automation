package com.surabhimarathe.interfaceautomation.discovery;

import java.util.List;
import java.util.UUID;

/** In-memory visible UI data, never written wholesale to evidence. */
public record Observation(UUID id, String url, String heading, List<Control> controls,
                          List<String> statuses, List<String> alerts, java.util.Map<String,String> details) {
    public Observation {
        heading = ContextText.clean(heading, 160);
        controls = List.copyOf(controls.stream().limit(40).toList());
        statuses = statuses.stream().limit(5).map(s -> ContextText.clean(s, 300)).toList();
        alerts = alerts.stream().limit(5).map(s -> ContextText.clean(s, 300)).toList();
        details = java.util.Map.copyOf(details);
    }
    public Observation(UUID id, String url, String heading, List<Control> controls) {
        this(id, url, heading, controls, List.of(), List.of(), java.util.Map.of());
    }
    public record Control(String id, String kind, String name, String context, boolean filled) {
        public Control {
            name = ContextText.clean(name, 160);
            context = ContextText.clean(context, 300);
        }
        public Control(String id, String kind, String name) { this(id, kind, name, "", false); }
    }
}
