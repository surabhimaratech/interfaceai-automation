package com.surabhimarathe.interfaceautomation.approval;

import com.surabhimarathe.interfaceautomation.replay.TenantId;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalCliTest {
    @TempDir Path temp;
    private static final String TENANT = "privateTenant";
    private static final String ACTOR = "private-operator@example.invalid";
    private Path artifact() throws Exception {
        Path file = temp.resolve("sensitive-artifact.json");
        if (!Files.exists(file)) Files.copy(Path.of("src/test/resources/artifacts/prepare-fee-reversal-review.v1.json"), file);
        return file;
    }
    private Map<String,String> env() {
        return new HashMap<>(Map.of("REPLAY_GOVERNANCE_DIRECTORY", temp.resolve("journal").toString(),
                "REPLAY_TENANT_ID", TENANT, "APPROVAL_ACTOR", ACTOR));
    }
    private String run(String command, String... extra) throws Exception {
        var args = new ArrayList<>(List.of(command, artifact().toString()));
        args.addAll(List.of(extra));
        String output = ApprovalCli.execute(args.toArray(String[]::new), env());
        assertSafe(output);
        return output;
    }
    private void assertSafe(String output) throws Exception {
        for (String value : List.of(TENANT, ACTOR, temp.toString(), artifact().toString(), "memberId",
                "Courtesy adjustment", "999999", "https://", "Exception", "\n", "artifactDigest", "tenantScopeHash"))
            assertFalse(output.contains(value));
        assertFalse(output.matches(".*[0-9a-f]{64}.*"));
    }
    private ApprovalService service() { return new ApprovalService(new FileGovernanceStore(temp.resolve("journal")), ApprovalPolicy.defaults(), Clock.systemUTC()); }
    private ArtifactIdentity identity() throws Exception { return ArtifactIdentity.from(new TenantId(TENANT), Files.readAllBytes(artifact())); }
    private void qualify() throws Exception {
        // Domain fixture setup, deliberately not an operator command.
        for (int i=0;i<5;i++) service().observe(identity(), ReliabilityObservation.SUCCESS);
    }
    @Test void lifecycleAndRedactedCriteria() throws Exception {
        assertTrue(run("register").contains("lifecycle=DRAFT"));
        assertTrue(run("status").contains("eligibleForApproval=false"));
        assertEquals("ApprovalResult[code=NOT_ELIGIBLE]", run("approve"));
        qualify();
        assertTrue(run("status").contains("eligibleForApproval=true"));
        String approved = run("approve");
        assertTrue(approved.contains("lifecycle=APPROVED"));
        assertTrue(approved.contains("handled=5, eligible=5, assisted=0, safetyFailures=0, scoreBasisPoints=5655"));
        assertTrue(approved.contains("approvedUnder.minimumEligibleRuns=5"));
        assertTrue(approved.contains("approvedUnder.minimumScoreBasisPoints=5000"));
        assertTrue(approved.contains("approvedUnder.requireZeroSafetyFailures=true"));
        String suspended = run("suspend", "OPERATOR_WITHDRAWAL");
        assertTrue(suspended.contains("lifecycle=SUSPENDED"));
        assertTrue(suspended.contains("suspensionReason=OPERATOR_WITHDRAWAL"));
        assertTrue(suspended.contains("approvedUnder.minimumEligibleRuns=5"));
        assertEquals(8, new FileGovernanceStore(temp.resolve("journal")).read().size());
    }
    @Test void exactBytesAndTenantSelectDifferentIdentities() throws Exception {
        run("register");
        var other = env(); other.put("REPLAY_TENANT_ID", "otherTenant");
        assertEquals("ApprovalResult[code=UNKNOWN_IDENTITY]", ApprovalCli.execute(new String[]{"status", artifact().toString()}, other));
        Files.writeString(artifact(), "\n", StandardOpenOption.APPEND);
        assertEquals("ApprovalResult[code=UNKNOWN_IDENTITY]", run("status"));
        assertTrue(run("register").contains("lifecycle=DRAFT"));
        assertEquals(2, new FileGovernanceStore(temp.resolve("journal")).read().size());
    }
    @Test void duplicateAndInvalidTransitionsAreFixed() throws Exception {
        run("register");
        assertEquals("ApprovalResult[code=INVALID_TRANSITION]", run("register"));
        assertEquals("ApprovalResult[code=INVALID_TRANSITION]", run("suspend", "SAFETY_REVIEW"));
        qualify(); run("approve");
        assertEquals("ApprovalResult[code=INVALID_TRANSITION]", run("approve"));
        run("suspend", "SAFETY_REVIEW");
        assertEquals("ApprovalResult[code=INVALID_TRANSITION]", run("approve"));
    }
    @Test void prohibitedCommandsCannotMutateCounters() throws Exception {
        run("register");
        for (String command : List.of("observe", "record-success", "reset", "delete", "reapprove", "edit-policy", "edit-journal"))
            assertEquals("ApprovalResult[code=INVALID_COMMAND]", run(command));
        assertEquals(Reliability.empty(), service().state(identity()).reliability());
        assertEquals(1, new FileGovernanceStore(temp.resolve("journal")).read().size());
    }
    @Test void invalidSyntaxNeverCreatesJournal() throws Exception {
        for (String[] args : new String[][]{ {}, {"status"}, {"suspend", artifact().toString()},
                {"suspend", artifact().toString(), "secret reason"}, {"register", artifact().toString(), "extra"}, {"observe"}})
            assertEquals("ApprovalResult[code=INVALID_COMMAND]", ApprovalCli.execute(args, env()));
        assertFalse(Files.exists(temp.resolve("journal")));
    }
    @Test void missingConfigurationNeverCreatesJournal() throws Exception {
        for (String key : List.of("REPLAY_GOVERNANCE_DIRECTORY", "REPLAY_TENANT_ID")) {
            var environment = env(); environment.remove(key);
            assertEquals("ApprovalResult[code=INVALID_CONFIGURATION]", ApprovalCli.execute(new String[]{"register", artifact().toString()}, environment));
            environment.put(key, " ");
            assertEquals("ApprovalResult[code=INVALID_CONFIGURATION]", ApprovalCli.execute(new String[]{"register", artifact().toString()}, environment));
        }
        assertFalse(Files.exists(temp.resolve("journal")));
    }
    @Test void actorIsValidatedOnlyForApprovalBeforeStorage() throws Exception {
        for (String actor : List.of("", " ", "bad\nactor", "x".repeat(161))) {
            var environment = env(); environment.put("APPROVAL_ACTOR", actor);
            assertEquals("ApprovalResult[code=INVALID_ACTOR]", ApprovalCli.execute(new String[]{"approve", artifact().toString()}, environment));
        }
        var environment = env(); environment.remove("APPROVAL_ACTOR");
        assertEquals("ApprovalResult[code=INVALID_ACTOR]", ApprovalCli.execute(new String[]{"approve", artifact().toString()}, environment));
        assertFalse(Files.exists(temp.resolve("journal")));
        assertTrue(ApprovalCli.execute(new String[]{"register", artifact().toString()}, environment).contains("code=OK"));
    }
    @Test void invalidArtifactsNeverCreateJournal() throws Exception {
        assertEquals("ApprovalResult[code=INVALID_ARTIFACT]", ApprovalCli.execute(new String[]{"register", temp.resolve("missing").toString()}, env()));
        for (byte[] bytes : List.of(new byte[]{(byte)0xff}, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[128001])) {
            Files.write(artifact(), bytes);
            assertEquals("ApprovalResult[code=INVALID_ARTIFACT]", run("register"));
        }
        assertFalse(Files.exists(temp.resolve("journal")));
    }
    @Test void corruptAndUnavailableStorageFailClosed() throws Exception {
        Files.createDirectory(temp.resolve("journal"));
        Files.writeString(temp.resolve("journal/private-secret"), ACTOR);
        assertEquals("ApprovalResult[code=CORRUPT_HISTORY]", run("status"));
        var environment = env(); environment.put("REPLAY_GOVERNANCE_DIRECTORY", artifact().toString());
        String output = ApprovalCli.execute(new String[]{"status", artifact().toString()}, environment);
        assertEquals("ApprovalResult[code=STORAGE_FAILURE]", output);
        assertSafe(output);
    }
}
