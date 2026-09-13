package com.surabhimarathe.interfaceautomation.discovery;

import java.time.Duration;

public interface DiscoverySurface {
    Observation open(String url);
    Observation observe();
    ActionResult execute(UiAction action);
    void budget(Duration remaining);
    void waitBriefly();
}
