package com.surabhimarathe.interfaceautomation.discovery;

import com.surabhimarathe.interfaceautomation.artifact.*;
import java.math.BigDecimal;
import java.util.*;

/** Trusted application contract. Steps are intentionally empty and must come from execution. */
public final class FeeReviewCapability {
    private FeeReviewCapability() {}
    public static TrustedCapabilityDefinition definition() {
        Map<String, InputSpec> inputs = Map.of(
                "memberId", new InputSpec(ValueType.STRING, string(6, 6), "Member identifier used to find the record."),
                "amount", new InputSpec(ValueType.DECIMAL, decimal("0.01", "100"), "Requested fee reversal amount."),
                "reason", new InputSpec(ValueType.STRING, string(1, 120), "Reason for the requested fee reversal."));
        Map<String, OutputSpec> outputs = new LinkedHashMap<>();
        outputs.put("memberId", new OutputSpec(ValueType.STRING, string(6,6), "Member identifier on the review."));
        outputs.put("memberName", new OutputSpec(ValueType.STRING, string(1,160), "Member name on the review."));
        outputs.put("accountId", new OutputSpec(ValueType.STRING, string(1,32), "Account identifier on the review."));
        outputs.put("currentBalance", new OutputSpec(ValueType.DECIMAL, decimal("0","1000000000000"), "Balance before the proposed reversal."));
        outputs.put("amount", new OutputSpec(ValueType.DECIMAL, decimal("0.01","100"), "Requested reversal amount on the review."));
        outputs.put("reason", new OutputSpec(ValueType.STRING, string(1,120), "Reason shown on the review."));
        outputs.put("projectedBalance", new OutputSpec(ValueType.DECIMAL, decimal("0","1000000000000"), "Projected balance after the proposed reversal."));
        List<ExtractorSpec> extractors = List.of(
                extractor("memberId", "Member ID", false, "memberId"), extractor("memberName", "Name", false, null),
                extractor("accountId", "Account", false, null), extractor("currentBalance", "Current balance", true, null),
                extractor("amount", "Reversal amount", true, "amount"), extractor("reason", "Reason", false, "reason"),
                extractor("projectedBalance", "Projected balance", true, null));
        CapabilityArtifact draft = new CapabilityArtifact(1, 1, "prepare-fee-reversal-review",
                "Prepare fee reversal review", "Find a member and prepare a fee reversal for review without submitting it.",
                new ExecutionBoundary(ExecutionBoundary.Mode.REVIEW_ONLY), new TargetSpec("legacy-banking", "/legacy"),
                inputs, outputs, List.of(), List.of(
                        new OutcomeSpec("MEMBER_NOT_FOUND", visible(LocatorSpec.Role.STATUS, "Member not found")),
                        new OutcomeSpec("VALIDATION_REJECTED", visible(LocatorSpec.Role.ALERT, "VALIDATION_REJECTED"))),
                new CheckpointSpec(locator(LocatorSpec.Role.HEADING, "Fee Reversal Review"), extractors),
                new Provenance(Provenance.Source.HAND_AUTHORED_EXAMPLE, null));
        return new TrustedCapabilityDefinition(draft, Set.of("Member Search", "Search Results", "Member Details",
                "Savings Account", "Prepare Fee Reversal", "Fee Reversal Review"), Set.of("Savings"));
    }
    public static Map<String, Object> parameters(ReviewCheckpoint.Request request) {
        return Map.of("memberId", request.memberId(), "amount", request.amount(), "reason", request.reason());
    }
    private static Constraints string(int min, int max) { return new Constraints(min,max,null,null,null); }
    private static Constraints decimal(String min, String max) {
        return new Constraints(null,null,new BigDecimal(min),new BigDecimal(max),2);
    }
    private static LocatorSpec locator(LocatorSpec.Role role, String name) {
        return new LocatorSpec(role,name,LocatorSpec.NameMatch.EXACT,null,LocatorSpec.Cardinality.EXACT_ONE);
    }
    private static PostconditionSpec visible(LocatorSpec.Role role, String name) {
        return new PostconditionSpec(PostconditionSpec.Kind.VISIBLE,locator(role,name),null);
    }
    private static ExtractorSpec extractor(String output, String label, boolean money, String input) {
        return new ExtractorSpec(output, new LocatorSpec(LocatorSpec.Role.ROW,label,LocatorSpec.NameMatch.PREFIX,null,
                LocatorSpec.Cardinality.EXACT_ONE), ExtractorSpec.Read.ROW_VALUE,
                money ? ExtractorSpec.Format.USD_DECIMAL : ExtractorSpec.Format.TEXT,
                input == null ? null : "${inputs." + input + "}");
    }
}
