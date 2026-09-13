package com.surabhimarathe.interfaceautomation.discovery;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgressGuardTest {
    Observation page() { return new Observation(UUID.randomUUID(), "/legacy", "Search", List.of(new Observation.Control("c0","textbox","Member ID"))); }
    @Test void repeatedSemanticActionUsesFreshIdsAndStopsThirdAttempt() {
        var guard = new ProgressGuard(3);
        for (int i=0; i<3; i++) {
            var o = page();
            assertEquals(i == 2, guard.blocks(o, new UiAction(o.id(), UiAction.Type.FILL, "c0", "private-value")));
        }
        guard.reset();
        var o = page();
        assertFalse(guard.blocks(o, new UiAction(o.id(), UiAction.Type.FILL, "c0", "private-value")));
    }
    @Test void noProgressIsBoundedEvenWithChangingValues() {
        var guard = new ProgressGuard(3);
        var o = page();
        for (int i=0; i<3; i++) {
            assertFalse(guard.blocks(o, new UiAction(o.id(), UiAction.Type.FILL, "c0", "value"+i)));
            assertFalse(guard.record(o, page()));
        }
        assertTrue(guard.blocks(o, new UiAction(o.id(), UiAction.Type.WAIT, "", "")));
        assertFalse(guard.blocks(o, new UiAction(o.id(), UiAction.Type.COMPLETE, "", "")));
    }
    @Test void summaryContainsOnlyEnumsAndBoolean() {
        var summary = new PreviousAction(UiAction.Type.FILL, ActionResult.Code.VALUE_VERIFIED, true);
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(summary);
        assertEquals("{\"action\":\"FILL\",\"result\":\"VALUE_VERIFIED\",\"stateChanged\":true}", json);
    }
}
