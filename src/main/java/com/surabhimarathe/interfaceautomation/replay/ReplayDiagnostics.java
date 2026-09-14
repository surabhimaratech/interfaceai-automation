package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;
import java.util.*;
import static com.surabhimarathe.interfaceautomation.replay.ReplayDiagnostic.*;

/** One run's synchronized, sanitized snapshot, also readable by the overall-deadline caller. */
final class ReplayDiagnostics {
    private final List<String> secrets;
    private int step;
    private String stepId;
    private Phase phase = Phase.VALIDATION;
    private ExpectedLocator expected;
    private MatchCounts matches = MatchCounts.unknown();
    private boolean actionStarted, requestDenied, policyDenied, dialog, popup, expiry, handingOff;
    private int handoffs;

    ReplayDiagnostics(Map<String, Object> inputs) {
        secrets = inputs.values().stream().filter(Objects::nonNull).map(Object::toString).filter(s -> !s.isBlank()).toList();
    }
    synchronized void step(int number, String id) {
        step = number; stepId = safeId(id); actionStarted = false;
        phase(Phase.LOCATOR);
    }
    synchronized void phase(Phase value) { phase = value; expected = null; matches = MatchCounts.unknown(); }
    synchronized void expected(LocatorSpec spec) {
        expected = new ExpectedLocator(spec.role(), safe(spec.accessibleName()), spec.nameMatch(),
                spec.context() == null ? null : new ContextSpec(spec.context().kind(), safe(spec.context().text())), spec.cardinality());
        matches = MatchCounts.unknown();
    }
    synchronized void count(int count) { matches = MatchCounts.of(count); }
    synchronized void actionStarted() { actionStarted = true; }
    synchronized boolean hasActionStarted() { return actionStarted; }
    synchronized void expiry() { expiry = true; }
    synchronized void beginHandoff() { handingOff = true; handoffs++; }
    synchronized void endHandoff() { handingOff = false; }
    synchronized void deniedRequest() { requestDenied = true; }
    synchronized void deniedPolicy() { policyDenied = true; }
    synchronized void dialog() { dialog = true; }
    synchronized void popup() { popup = true; }
    synchronized ReplayResult.Code safetyCode(ReplayResult.Code fallback) {
        if (requestDenied || policyDenied) return ReplayResult.Code.POLICY_DENIED;
        if (dialog) return ReplayResult.Code.UNEXPECTED_DIALOG;
        if (popup) return ReplayResult.Code.BROWSER_FAILURE;
        if (handingOff && fallback == ReplayResult.Code.TIMEOUT) return ReplayResult.Code.HANDOFF_TIMEOUT;
        return fallback;
    }
    synchronized ReplayResult failure(ReplayResult.Code fallback) {
        var code = safetyCode(fallback);
        return ReplayResult.failure(code, step).withDiagnostic(snapshot(code));
    }
    synchronized ReplayDiagnostic snapshot(ReplayResult.Code code) {
        return new ReplayDiagnostic(code, step, stepId, phase, expected, matches,
                new Conditions(actionStarted, requestDenied, policyDenied || code == ReplayResult.Code.POLICY_DENIED,
                        dialog, popup, code == ReplayResult.Code.TIMEOUT || code == ReplayResult.Code.HANDOFF_TIMEOUT,
                        code == ReplayResult.Code.POSTCONDITION_FAILED, code == ReplayResult.Code.CHECKPOINT_FAILED,
                        code == ReplayResult.Code.BUSINESS_OUTCOME, expiry, handoffs,
                        code == ReplayResult.Code.HUMAN_ACTION_REQUIRED, code == ReplayResult.Code.HANDOFF_TIMEOUT,
                        code == ReplayResult.Code.HANDOFF_LIMIT));
    }
    synchronized void restore(ReplayDiagnostic previous) {
        // Restore only lookup context; sticky safety flags must never be rolled back.
        phase = previous.phase(); expected = previous.expected(); matches = previous.matches();
    }
    private String safeId(String value) {
        if (value == null) return null;
        // Validated step identifiers may contain digits; invocation values still cannot escape.
        return value.length() <= 80 && value.matches("[A-Za-z][A-Za-z0-9_-]*")
                && secrets.stream().noneMatch(value::contains) ? value : "[REDACTED]";
    }
    private String safe(String value) {
        if (value == null) return null;
        // Preserve whole input expressions without ever substituting values.
        if (value.matches("\\$\\{inputs\\.[A-Za-z][A-Za-z0-9_]*}")) return value;
        if (value.length() > 300 || value.chars().anyMatch(Character::isISOControl)
                || value.matches("(?is).*(https?://|www\\.|@|\\d|<|>|sk-|bearer|token|secret|password).*")
                || secrets.stream().anyMatch(s -> value.toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT))))
            return "[REDACTED]";
        return value;
    }
}
