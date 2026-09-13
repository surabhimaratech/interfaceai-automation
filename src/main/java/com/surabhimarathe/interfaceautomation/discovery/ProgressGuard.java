package com.surabhimarathe.interfaceautomation.discovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded in-memory fingerprints; no raw inputs or page content are retained here or logged. */
public final class ProgressGuard {
    private final int limit;
    private int unchanged;
    private final LinkedHashMap<String,Integer> visits = new LinkedHashMap<>();
    public ProgressGuard(int limit) {
        if (limit < 2 || limit > 10) throw new IllegalArgumentException("INVALID_PROGRESS_LIMIT");
        this.limit = limit;
    }
    public boolean blocks(Observation observed, UiAction action) {
        if (action.type() == UiAction.Type.COMPLETE || action.type() == UiAction.Type.REQUEST_HUMAN) return false;
        var target = observed.controls().stream().filter(c -> c.id().equals(action.controlId())).findFirst();
        String key = digest(fingerprint(observed) + action.type() + target.map(c -> c.kind() + c.name() + c.context()).orElse("")
            + digest(action.value() == null ? "" : action.value()));
        int count = visits.merge(key, 1, Integer::sum);
        if (visits.size() > 16) visits.remove(visits.keySet().iterator().next());
        return count >= limit || unchanged >= limit;
    }
    public boolean record(Observation before, Observation after) {
        boolean changed = after != null && !fingerprint(before).equals(fingerprint(after));
        unchanged = changed ? 0 : unchanged + 1;
        return changed;
    }
    public void reset() { visits.clear(); unchanged = 0; }
    private static String fingerprint(Observation o) {
        return digest(o.url() + o.heading() + o.controls() + o.statuses() + o.alerts() + o.details());
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
