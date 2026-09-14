package com.surabhimarathe.interfaceautomation.replay;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayResultTest {
    private Status status(Code code) {
        return switch (code) {
            case CHECKPOINT_VERIFIED -> Status.SUCCEEDED;
            case BUSINESS_OUTCOME -> Status.EXPECTED_OUTCOME;
            case POLICY_DENIED -> Status.BLOCKED;
            default -> Status.FAILED;
        };
    }
    private Disposition disposition(Code code) {
        return switch (code) {
            case CHECKPOINT_VERIFIED -> Disposition.SUCCESS;
            case BUSINESS_OUTCOME -> Disposition.EXPECTED_OUTCOME;
            case POLICY_DENIED -> Disposition.POLICY_BLOCK;
            case TIMEOUT, HUMAN_ACTION_REQUIRED -> Disposition.RECOVERABLE;
            default -> Disposition.HARD_FAILURE;
        };
    }
    private String outcome(Status status) { return status == Status.EXPECTED_OUTCOME ? "MEMBER_NOT_FOUND" : null; }
    private ReplayResult result(Status status, Code code, Disposition disposition, String outcome) {
        return new ReplayResult(status,code,outcome,2,Map.of(),disposition,null,DiagnosticPersistence.DISABLED);
    }

    @Test void everyValidStatusCodeAndDispositionStillWorks() {
        for (Code code : Code.values()) {
            var result = result(status(code),code,disposition(code),outcome(status(code)));
            assertEquals(status(code),result.status());
            assertEquals(disposition(code),result.disposition());
            assertEquals(code,result.code());
            assertTrue(result.toString().contains("disposition="+disposition(code)));
            assertEquals(result,new ReplayResult(status(code),code,outcome(status(code)),2,Map.of()));
        }
    }

    @Test void allMismatchedStatusCodePairsAreRejected() {
        for (Status status : Status.values()) for (Code code : Code.values()) {
            if (status == status(code)) continue;
            assertThrows(IllegalArgumentException.class,() -> result(status,code,disposition(code),outcome(status)));
            assertThrows(IllegalArgumentException.class,() -> new ReplayResult(status,code,outcome(status),2,Map.of()));
        }
    }

    @Test void allMismatchedDispositionsAreRejected() {
        for (Code code : Code.values()) for (Disposition disposition : Disposition.values()) {
            if (disposition == disposition(code)) continue;
            assertThrows(IllegalArgumentException.class,() -> result(status(code),code,disposition,outcome(status(code))));
        }
    }

    @Test void mandatoryFieldsCannotBeNull() {
        assertThrows(IllegalArgumentException.class,() -> result(null,Code.TIMEOUT,Disposition.RECOVERABLE,null));
        assertThrows(IllegalArgumentException.class,() -> result(Status.FAILED,null,Disposition.HARD_FAILURE,null));
        assertThrows(IllegalArgumentException.class,() -> result(Status.FAILED,Code.TIMEOUT,null,null));
        assertThrows(IllegalArgumentException.class,() -> new ReplayResult(Status.FAILED,Code.TIMEOUT,null,2,null,
                Disposition.RECOVERABLE,null,DiagnosticPersistence.DISABLED));
        assertThrows(IllegalArgumentException.class,() -> new ReplayResult(Status.FAILED,Code.TIMEOUT,null,2,Map.of(),
                Disposition.RECOVERABLE,null,null));
    }

    @Test void outcomeIdentifierIsRequiredBoundedAndArtifactCompatible() {
        for (String invalid : new String[]{null,""," ","MEMBER NOT FOUND","MEMBER-NOT-FOUND","1OUTCOME",
                "_OUTCOME","MEMBER\nNOT_FOUND","https://private/member","A".repeat(81)}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> result(Status.EXPECTED_OUTCOME,Code.BUSINESS_OUTCOME,Disposition.EXPECTED_OUTCOME,invalid));
            assertEquals("INVALID_REPLAY_RESULT",error.getMessage());
        }
        for (String valid : new String[]{"MEMBER_NOT_FOUND","VALIDATION_REJECTED","Outcome_2","A".repeat(80)})
            assertEquals(valid,result(Status.EXPECTED_OUTCOME,Code.BUSINESS_OUTCOME,Disposition.EXPECTED_OUTCOME,valid).effectiveCode());
    }

    @Test void otherStatusesRejectAnyOutcomeCode() {
        for (Code code : Code.values()) {
            if (code == Code.BUSINESS_OUTCOME) continue;
            for (String unexpected : new String[]{"MEMBER_NOT_FOUND",""})
                assertThrows(IllegalArgumentException.class,() -> result(status(code),code,disposition(code),unexpected));
        }
    }

    @Test void outputsAreDefensivelyCopiedAndPresentationRemainsRedacted() {
        var source = new HashMap<String,Object>();
        source.put("memberName","PRIVATE_MEMBER");
        var result = new ReplayResult(Status.SUCCEEDED,Code.CHECKPOINT_VERIFIED,null,8,source,
                Disposition.SUCCESS,null,DiagnosticPersistence.DISABLED);
        source.put("memberName","CHANGED");
        assertEquals("PRIVATE_MEMBER",result.outputs().get("memberName"));
        assertThrows(UnsupportedOperationException.class,() -> result.outputs().put("extra","value"));
        assertEquals("ReplayResult[status=SUCCEEDED, disposition=SUCCESS, code=CHECKPOINT_VERIFIED, step=8, outputs=REDACTED]",
                result.toString());
        assertFalse(result.toString().contains("PRIVATE_MEMBER"));
    }
}
