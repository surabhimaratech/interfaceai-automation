package com.surabhimarathe.interfaceautomation.discovery;

public record ActionResult(Status status, Code code, Observation observation) {
    public enum Status { SUCCEEDED, BLOCKED, FAILED }
    public enum Code { VALUE_VERIFIED, CLICK_COMPLETED, POLICY_DENIED, STALE_OBSERVATION,
        TARGET_CHANGED, BROWSER_FAILURE }
}
