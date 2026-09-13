package com.surabhimarathe.interfaceautomation.discovery;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

/** Target-specific checkpoint, not a reusable artifact or a model judgement. */
public final class ReviewCheckpoint {
    public record Request(String memberId, String accountId, BigDecimal amount, String reason) {
        public Request {
            if (memberId == null || !memberId.matches("[0-9]{6}") || accountId == null || !accountId.matches("SAV-[0-9]+")
                || amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("100")) > 0
                || amount.stripTrailingZeros().scale() > 2 || reason == null || reason.isBlank() || reason.length() > 120)
                throw new IllegalArgumentException("INVALID_REVIEW_REQUEST");
        }
        public String goal() {
            return "Find member " + memberId + ", open savings account " + accountId + ", prepare a fee reversal of "
                + amount.toPlainString() + " with reason " + reason
                + ". Reach Fee Reversal Review, return its details and COMPLETE. Never submit.";
        }
    }
    public record Details(String memberId, String name, String accountId, BigDecimal currentBalance,
                          BigDecimal amount, String reason, BigDecimal projectedBalance) {}
    public static Details verify(Observation observed, Request requested) {
        try {
            if (!URI.create(observed.url()).getPath().equals("/legacy/accounts/" + requested.accountId() + "/fee-reversal/review")
                || !observed.heading().equals("Fee Reversal Review") || !observed.alerts().isEmpty()
                || !observed.statuses().contains("Ready for review — not submitted.")) return null;
            Map<String,String> d = observed.details();
            if (d.size() != 7 || !requested.memberId().equals(d.get("Member ID"))
                || !requested.accountId().equals(d.get("Account")) || !requested.reason().equals(d.get("Reason"))
                || d.get("Name").isBlank()) return null;
            BigDecimal before = money(d.get("Current balance")), amount = money(d.get("Reversal amount")),
                after = money(d.get("Projected balance"));
            if (requested.amount().compareTo(amount) != 0 || before.add(amount).compareTo(after) != 0) return null;
            return new Details(d.get("Member ID"), d.get("Name"), d.get("Account"), before, amount, d.get("Reason"), after);
        } catch (RuntimeException e) { return null; }
    }
    private static BigDecimal money(String text) {
        if (text == null || !text.matches("\\$[0-9]+\\.[0-9]{2}")) throw new IllegalArgumentException();
        return new BigDecimal(text.substring(1));
    }
}
