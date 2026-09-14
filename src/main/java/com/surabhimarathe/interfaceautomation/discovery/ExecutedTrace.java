package com.surabhimarathe.interfaceautomation.discovery;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.surabhimarathe.interfaceautomation.artifact.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static com.surabhimarathe.interfaceautomation.discovery.CompilationResult.Code.*;

/** Browser-owner-only capture. It never consumes a model's locator, postcondition or page summary. */
final class ExecutedTrace {
    private final TrustedCapabilityDefinition definition;
    private final Map<String, Object> inputs;
    private final ActionPolicy policy;
    private final DiscoveryTrace trace;
    private final UUID runId;
    private final ArtifactStore store;
    private volatile boolean tainted, invalid, finished;
    private int successful;
    private CompilationResult result;

    ExecutedTrace(TrustedCapabilityDefinition definition, Map<String, Object> inputs,
                  ActionPolicy policy, UUID runId, Path outputDirectory) {
        this.definition = definition; this.inputs = Map.copyOf(inputs); this.policy = policy; this.runId = runId;
        this.trace = new DiscoveryTrace(runId);
        this.store = outputDirectory == null ? null : new ArtifactStore(outputDirectory);
        result = new CompilationResult(INCOMPLETE, runId, 0, null);
    }
    void bind(UUID id, boolean correlated) { if (!runId.equals(id) || !correlated) tainted = true; }
    void manual() { nonReplayable(); }
    void nonReplayable() { tainted = true; }
    void invalidate() { invalid = true; }
    CompilationResult result() { return finished ? result : new CompilationResult(INCOMPLETE, runId, trace.size(), null); }

    LocatorSpec before(Page page, ElementHandle target, UiAction action) {
        try {
            if (finished) { tainted = true; return null; }
            LocatorSpec.Role role = action.type() == UiAction.Type.FILL ? LocatorSpec.Role.TEXTBOX :
                    "A".equals(target.evaluate("e => e.tagName")) ? LocatorSpec.Role.LINK : LocatorSpec.Role.BUTTON;
            String observedName = BrowserPolicyGuard.controlName(target);
            // Emit only a matching trusted policy label, never an unreviewed page string.
            String name = policy.controlNames().getOrDefault(action.type(), Set.of()).stream()
                    .filter(observedName::equals).findFirst().orElseThrow();
            if (!safe(name, 160)) throw new IllegalArgumentException();
            LocatorSpec plain = locator(role, name, null);
            if (unique(page, plain, target)) return plain;
            List<String> cells = rowCells(target);
            // First prefer host-approved stable semantic tokens observed as whole cells.
            for (String anchor : definition.rowAnchors().stream().sorted().toList()) {
                if (!cells.contains(anchor) || !safe(anchor, 300)) continue;
                LocatorSpec scoped = locator(role, name, new ContextSpec(ContextSpec.Kind.ROW, anchor));
                if (unique(page, scoped, target)) return scoped;
            }
            // Otherwise a whole STRING input cell may supply a parameterized row discriminator.
            for (var e : inputs.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                if (!(e.getValue() instanceof String value) || value.isBlank() || value.length() > 300 || !cells.contains(value)) continue;
                if (inputs.values().stream().filter(value::equals).count() != 1) throw new IllegalArgumentException();
                LocatorSpec scoped = locator(role, name, new ContextSpec(ContextSpec.Kind.ROW, "${inputs." + e.getKey() + "}"));
                if (unique(page, scoped, target)) return scoped;
            }
            throw new IllegalArgumentException();
        } catch (RuntimeException ex) { invalid = true; return null; }
    }

    PostconditionSpec after(Page page, UiAction action) {
        try { return action.type() == UiAction.Type.CLICK ? heading(page) : null; }
        catch (RuntimeException ex) { invalid = true; return null; }
    }

    void succeeded(UiAction action, ActionResult result, LocatorSpec locator, PostconditionSpec post) {
        successful++;
        if (invalid || locator == null || finished) { invalid = true; return; }
        try {
            trace.recordSuccessful(action, result, locator, post);
        } catch (RuntimeException ex) { invalid = true; }
    }

    synchronized void finish(DiscoveryRunner.Result discovery) {
        if (finished) return;
        finished = true;
        CompilationResult.Code code;
        CapabilityArtifact artifact = null;
        if (tainted || !runId.equals(discovery.runId())) code = TRACE_NOT_REPLAYABLE;
        else if (discovery.state() != RunState.SUCCEEDED || discovery.code() != ActionResult.Code.CHECKPOINT_VERIFIED)
            code = DISCOVERY_NOT_VERIFIED;
        else if (invalid || successful != trace.size()) code = SEMANTIC_CAPTURE_FAILED;
        else {
            try {
                artifact = new ArtifactCompiler().compile(definition.draft(), trace, inputs);
                code = COMPILED;
                if (store != null) { store.persist(artifact); code = PERSISTED; }
            } catch (FileAlreadyExistsException ex) { code = OUTPUT_COLLISION; artifact = null; }
            catch (java.io.IOException ex) { code = PERSISTENCE_FAILED; artifact = null; }
            catch (RuntimeException ex) { code = COMPILATION_REJECTED; artifact = null; }
        }
        result = new CompilationResult(code, runId, trace.size(), artifact);
    }

    private PostconditionSpec heading(Page page) {
        var primary = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setLevel(1));
        List<ElementHandle> headings = primary.elementHandles();
        try {
            var visible = headings.stream().filter(ElementHandle::isVisible).toList();
            if (visible.size() != 1) throw new IllegalArgumentException();
            String observed = BrowserPolicyGuard.controlName(visible.getFirst());
            String name = definition.headings().stream().filter(observed::equals).findFirst().orElseThrow();
            if (!safe(name, 160)) throw new IllegalArgumentException();
            LocatorSpec spec = locator(LocatorSpec.Role.HEADING, name, null);
            if (!unique(page, spec, visible.getFirst())) throw new IllegalArgumentException();
            return new PostconditionSpec(PostconditionSpec.Kind.VISIBLE, spec, null);
        } finally { headings.forEach(ElementHandle::dispose); }
    }

    private boolean unique(Page page, LocatorSpec spec, ElementHandle target) {
        var selector = page.getByRole(AriaRole.valueOf(spec.role().name()),
                new Page.GetByRoleOptions().setName(spec.accessibleName()).setExact(true));
        if (selector.count() > 200) return false;
        List<ElementHandle> candidates = selector.elementHandles();
        try {
            int count = 0;
            boolean same = false;
            for (ElementHandle candidate : candidates) {
                if (!candidate.isVisible()) continue;
                if (spec.context() != null) {
                    String text = spec.context().text();
                    if (text.startsWith("${inputs.")) text = (String) inputs.get(text.substring(9, text.length()-1));
                    if (!(Boolean) candidate.evaluate("""
                        (e, expected) => {
                            const row = e.closest('tr,[role=row]');
                            if (!row) return false;
                            const text = row.innerText.replace(/\\s+/g, ' ').trim();
                            return text.length <= 2000 && text.includes(expected);
                        }
                        """, text)) continue;
                }
                count++;
                same |= (Boolean) candidate.evaluate("(e, selected) => e === selected", target);
            }
            return count == 1 && same;
        } finally { candidates.forEach(ElementHandle::dispose); }
    }

    @SuppressWarnings("unchecked")
    private List<String> rowCells(ElementHandle target) {
        return (List<String>) target.evaluate("""
            e => {
                const row = e.closest('tr,[role=row]');
                if (!row || row.innerText.length > 2000) return [];
                return Array.from(row.children)
                    .filter(c => c.matches('td,th,[role=cell],[role=rowheader],[role=columnheader]') &&
                        !c.querySelector('a,button,input,textarea'))
                    .map(c => c.innerText.replace(/\\s+/g, ' ').trim()).filter(t => t.length > 0 && t.length <= 300);
            }
            """);
    }
    private boolean safe(String text, int max) {
        if (text.isBlank() || text.length() > max || text.codePoints().anyMatch(Character::isISOControl)) return false;
        for (Object value : inputs.values()) {
            String raw = value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : (String) value;
            if (!raw.isEmpty() && text.contains(raw)) return false;
        }
        return true;
    }
    private static LocatorSpec locator(LocatorSpec.Role role, String name, ContextSpec context) {
        return new LocatorSpec(role,name,LocatorSpec.NameMatch.EXACT,context,LocatorSpec.Cardinality.EXACT_ONE);
    }
}
