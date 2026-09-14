package com.surabhimarathe.interfaceautomation.replay;

import java.util.*;

/** Immutable host additions. The unremovable baseline is applied first by ReplayDiagnostics. */
public final class DiagnosticRedactionPolicy {
    private final List<String> literals;
    private final List<SafePattern> patterns;
    public DiagnosticRedactionPolicy(List<String> literalSecrets, List<String> safePatterns) {
        if (literalSecrets == null || safePatterns == null || literalSecrets.size() > 32 || safePatterns.size() > 16)
            throw invalid();
        literals = literalSecrets.stream().map(s -> {
            validateText(s); return s.toLowerCase(Locale.ROOT);
        }).toList();
        patterns = safePatterns.stream().map(SafePattern::new).toList();
    }
    public static DiagnosticRedactionPolicy baseline() { return new DiagnosticRedactionPolicy(List.of(),List.of()); }
    String apply(String text) {
        if (text == null || text.equals("[REDACTED]")) return text;
        if (text.length() > 300) return "[REDACTED]";
        String lower = text.toLowerCase(Locale.ROOT);
        return literals.stream().anyMatch(lower::contains) || patterns.stream().anyMatch(p -> p.matches(text))
                ? "[REDACTED]" : text;
    }
    private static void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 160 || text.codePoints().anyMatch(Character::isISOControl))
            throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_REDACTION_CONFIGURATION"); }
    @Override public String toString() { return "DiagnosticRedactionPolicy[REDACTED]"; }

    /**
     * Finite bounded pattern language, not Java regex. No backtracking or external regex engine.
     * Printable ASCII literals (excluding regex metacharacters), approved classes and {n}/{min,max}.
     * Matching is bounded by 32 atoms * 301 positions * 32 repetitions, per rule.
     */
    private static final class SafePattern {
        private record Atom(String characters, int minimum, int maximum) {}
        private final List<Atom> atoms;
        SafePattern(String text) {
            validateText(text);
            List<Atom> parsed = new ArrayList<>();
            int i = 0, expanded = 0;
            while (i < text.length()) {
                String chars;
                char c = text.charAt(i++);
                if (c == '[') {
                    int end = text.indexOf(']',i);
                    if (end < 0) throw invalid();
                    chars = switch (text.substring(i,end)) {
                        case "A-Z" -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
                        case "a-z" -> "abcdefghijklmnopqrstuvwxyz";
                        case "0-9" -> "0123456789";
                        case "A-Za-z" -> "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
                        case "A-Za-z0-9" -> "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                        case "A-Z0-9" -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                        default -> throw invalid();
                    };
                    i = end+1;
                } else {
                    if (c < 32 || c > 126 || "\\.^$|?*+(){}]".indexOf(c) >= 0) throw invalid();
                    chars = String.valueOf(c);
                }
                int minimum = 1, maximum = 1;
                if (i < text.length() && text.charAt(i) == '{') {
                    int end = text.indexOf('}',i);
                    if (end < 0) throw invalid();
                    String range = text.substring(i+1,end);
                    if (!range.matches("[0-9]{1,2}(,[0-9]{1,2})?")) throw invalid();
                    String[] parts = range.split(",");
                    minimum = Integer.parseInt(parts[0]);
                    maximum = parts.length == 1 ? minimum : Integer.parseInt(parts[1]);
                    if (minimum < 1 || maximum < minimum || maximum > 32) throw invalid();
                    i = end+1;
                }
                expanded += maximum;
                if (parsed.size() >= 32 || expanded > 256) throw invalid();
                parsed.add(new Atom(chars,minimum,maximum));
            }
            atoms = List.copyOf(parsed);
        }
        boolean matches(String text) {
            boolean[] reachable = new boolean[text.length()+1];
            Arrays.fill(reachable,true); // Search for a match starting at any position.
            for (Atom atom : atoms) {
                boolean[] next = new boolean[reachable.length];
                for (int start = 0; start < text.length(); start++) {
                    if (!reachable[start]) continue;
                    for (int repeat = 1; repeat <= atom.maximum() && start+repeat <= text.length(); repeat++) {
                        if (atom.characters().indexOf(text.charAt(start+repeat-1)) < 0) break;
                        if (repeat >= atom.minimum()) next[start+repeat] = true;
                    }
                }
                reachable = next;
            }
            for (boolean matched : reachable) if (matched) return true;
            return false;
        }
    }
}
