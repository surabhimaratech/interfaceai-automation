package com.surabhimarathe.interfaceautomation.discovery;

/** Best-effort sanitization for synthetic UI observations; not a general PII detector. */
public final class ContextText {
    private ContextText() {}
    public static String clean(String text, int limit) {
        String result = (text == null ? "" : text)
            .replaceAll("(?i)(password|token|secret|api[_ -]?key|authorization)\\s*[:=]\\s*\\S+", "$1=[REDACTED]")
            .replaceAll("(?i)bearer\\s+\\S+", "Bearer [REDACTED]")
            .replaceAll("sk-[a-zA-Z0-9_-]+", "[REDACTED]")
            .replaceAll("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}", "[EMAIL]")
            .replaceAll("\\b[0-9]{3}-[0-9]{2}-[0-9]{4}\\b", "[SSN]")
            .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        return result.substring(0, Math.min(limit, result.length()));
    }
}
