package com.surabhimarathe.interfaceautomation.approval;

import com.surabhimarathe.interfaceautomation.replay.TenantId;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.approval.ReliabilityObservation.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalState.Lifecycle.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.Code.*;

class ApprovalDomainTest {
    static final TenantId TENANT = new TenantId("approvalTenantA");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"),ZoneOffset.UTC);
    static byte[] fixture() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/artifacts/prepare-fee-reversal-review.v1.json"));
    }
    static ApprovalService service(GovernanceStore store) {
        return new ApprovalService(store,ApprovalPolicy.defaults(),CLOCK);
    }
    static void code(ApprovalException.Code code, org.junit.jupiter.api.function.Executable action) {
        var ex = assertThrows(ApprovalException.class,action);
        assertEquals(code,ex.code()); assertEquals(code.name(),ex.getMessage()); assertNull(ex.getCause());
    }
    static void five(ApprovalService service, ArtifactIdentity id) {
        for (int i=0;i<5;i++) service.observe(id,SUCCESS);
    }
    @Test void registrationIsDraftWithZeroConfidenceAndExactStableIdentity() throws Exception {
        var bytes = fixture();
        var store = new InMemoryGovernanceStore(); var service = service(store);
        var state = service.register(TENANT,bytes);
        assertEquals(DRAFT,state.lifecycle()); assertEquals(Reliability.empty(),state.reliability());
        assertFalse(service.eligible(state.identity()));
        assertEquals(ArtifactIdentity.from(TENANT,bytes),state.identity());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),state.identity().artifactDigest());
        assertEquals(Hashes.scoped("interfaceai:approval:tenant:v1",TENANT.value()),state.identity().tenantScopeHash());
        assertEquals(1,store.read().size());
        code(INVALID_TRANSITION,() -> service.register(TENANT,bytes));
    }
    @Test void formattingOneByteContentAndVersionChangesEachStartNewDraft() throws Exception {
        var store = new InMemoryGovernanceStore(); var service = service(store);
        String json = new String(fixture(),StandardCharsets.UTF_8);
        var original = service.register(TENANT,fixture()).identity(); five(service,original);
        service.approve(original,"human-reviewer");
        for (String changed : List.of(json+" ",json.replaceFirst("Prepare","prepare"),
                json.replaceFirst("\"artifactVersion\"\\s*:\\s*1","\"artifactVersion\":2"))) {
            var state=service.register(TENANT,changed.getBytes(StandardCharsets.UTF_8));
            assertNotEquals(original,state.identity()); assertEquals(DRAFT,state.lifecycle());
            assertEquals(Reliability.empty(),state.reliability()); assertNull(state.approvedAt());
        }
    }
    @Test void tenantScopeIsDistinctWithoutRawTenantInIdentityOrJson() throws Exception {
        var one=ArtifactIdentity.from(TENANT,fixture());
        var two=ArtifactIdentity.from(new TenantId("approvalTenantB"),fixture());
        assertNotEquals(one,two); assertEquals(one.artifactDigest(),two.artifactDigest());
        assertNotEquals(one.tenantScopeHash(),two.tenantScopeHash());
        var event = new GovernanceEvent(1,one,GovernanceEvent.Type.REGISTER,null,null,1,CLOCK.instant(),null,null);
        String json = new String(new GovernanceJson().write(event),StandardCharsets.UTF_8);
        for (String raw : List.of("approvalTenantA","approvalTenantB"))
            assertFalse((json+one+two+event).contains(raw));
    }
    @Test void invalidArtifactUtf8OrEmbeddedTenantCannotCreateIdentity() throws Exception {
        code(INVALID_ARTIFACT,() -> ArtifactIdentity.from(TENANT,"{}".getBytes(StandardCharsets.UTF_8)));
        code(INVALID_ARTIFACT,() -> ArtifactIdentity.from(TENANT,new byte[]{(byte)0xC3,0x28}));
        code(INVALID_IDENTITY,() -> ArtifactIdentity.from(null,fixture()));
        code(INVALID_IDENTITY,() -> ArtifactIdentity.from(TENANT,null));
        String embedded = new String(fixture(),StandardCharsets.UTF_8).replace("prepare-fee-reversal-review","APPROVALTENANTA");
        code(INVALID_IDENTITY,() -> ArtifactIdentity.from(TENANT,embedded.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void handledFailuresAndAssistanceHaveExactSeparateCounts() throws Exception {
        var service=service(new InMemoryGovernanceStore()); var id=service.register(TENANT,fixture()).identity();
        service.observe(id,SUCCESS); service.observe(id,EXPECTED_OUTCOME);
        int before=service.state(id).reliability().scoreBasisPoints();
        service.observe(id,HUMAN_ASSISTED);
        assertEquals(before,service.state(id).reliability().scoreBasisPoints());
        service.observe(id,RECOVERABLE_FAILURE);
        assertTrue(service.state(id).reliability().scoreBasisPoints()<before);
        service.observe(id,POLICY_BLOCK); service.observe(id,HARD_FAILURE);
        assertEquals(new Reliability(2,5,1,2,Reliability.wilson(2,5)),service.state(id).reliability());
    }
    @Test void wilsonMatchesIndependentHighPrecisionFixturesWithFloorRounding() {
        // Independently computed using Ruby BigDecimal/BigMath at 60 digits, z=1.96.
        long[][] fixtures={{0,0,0},{5,5,5655},{4,5,3755},{9,10,5958},{10,10,7224}};
        for(var f:fixtures) assertEquals(f[2],Reliability.wilson(f[0],f[1]));
        assertEquals(0,Reliability.wilson(0,10));
        code(INVALID_EVENT,() -> Reliability.wilson(2,1));
        code(INVALID_EVENT,() -> new Reliability(5,5,0,0,10000));
    }
    @Test void eligibleConfidenceNeverAutomaticallyApproves() throws Exception {
        var store=new InMemoryGovernanceStore(); var service=service(store);
        var id=service.register(TENANT,fixture()).identity(); five(service,id);
        assertTrue(service.eligible(id)); assertEquals(DRAFT,service.state(id).lifecycle());
        assertEquals(0,store.read().stream().filter(e -> e.type()==GovernanceEvent.Type.APPROVE).count());
    }
    @Test void explicitApprovalNeedsActorThresholdAndNoSafetyFailures() throws Exception {
        var service=service(new InMemoryGovernanceStore()); var id=service.register(TENANT,fixture()).identity();
        code(NOT_ELIGIBLE,() -> service.approve(id,"human-reviewer"));
        five(service,id);
        for(String invalid:new String[]{null,""," ","x".repeat(161),"raw\nactor","\uD800"})
            code(INVALID_ACTOR,() -> service.approve(id,invalid));
        assertEquals(DRAFT,service.state(id).lifecycle());
        service.observe(id,POLICY_BLOCK);
        for(int i=0;i<20;i++) service.observe(id,SUCCESS);
        assertTrue(service.state(id).reliability().scoreBasisPoints()>5000);
        code(NOT_ELIGIBLE,() -> service.approve(id,"human-reviewer"));
        var fresh=service.register(new TenantId("anotherTenant"),fixture()).identity();
        five(service,fresh); service.observe(fresh,HARD_FAILURE);
        code(NOT_ELIGIBLE,() -> service.approve(fresh,"human-reviewer"));
    }
    @Test void approvalRecordsOnlyActorHashAndInjectedTimeAndCannotRepeat() throws Exception {
        var store=new InMemoryGovernanceStore(); var service=service(store);
        var id=service.register(TENANT,fixture()).identity(); five(service,id);
        var state=service.approve(id,"human-reviewer");
        assertEquals(APPROVED,state.lifecycle()); assertEquals(CLOCK.instant(),state.approvedAt());
        assertEquals(Hashes.scoped("interfaceai:approval:actor:v1","human-reviewer"),state.approverHash());
        assertNotEquals(Hashes.scoped("interfaceai:approval:tenant:v1","human-reviewer"),state.approverHash());
        assertFalse(service.eligible(id)); code(INVALID_TRANSITION,() -> service.approve(id,"human-reviewer"));
        String output=state.toString()+store+service+store.read();
        assertFalse(output.contains("human-reviewer")); assertFalse(output.contains(TENANT.value()));
    }
    @Test void suspensionIsExplicitTerminalAndNeverInherited() throws Exception {
        var service=service(new InMemoryGovernanceStore()); var id=service.register(TENANT,fixture()).identity();
        code(INVALID_TRANSITION,() -> service.suspend(id,SuspensionReason.SAFETY_REVIEW));
        five(service,id); service.approve(id,"human-reviewer");
        service.observe(id,HARD_FAILURE); // Does not silently perform a lifecycle transition.
        assertEquals(APPROVED,service.state(id).lifecycle());
        var suspended=service.suspend(id,SuspensionReason.SAFETY_REVIEW);
        assertEquals(SUSPENDED,suspended.lifecycle()); assertEquals(SuspensionReason.SAFETY_REVIEW,suspended.suspensionReason());
        code(INVALID_TRANSITION,() -> service.approve(id,"human-reviewer"));
        code(INVALID_TRANSITION,() -> service.observe(id,SUCCESS));
        code(INVALID_TRANSITION,() -> service.suspend(id,SuspensionReason.TARGET_CHANGED));
        var fresh=service.register(TENANT,(new String(fixture(),StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8));
        assertEquals(DRAFT,fresh.lifecycle());
    }
    @Test void unknownIdentityAndInvalidEnumsFailWithoutMutation() throws Exception {
        var store=new InMemoryGovernanceStore(); var service=service(store);
        var id=ArtifactIdentity.from(TENANT,fixture());
        code(UNKNOWN_IDENTITY,() -> service.state(id));
        code(UNKNOWN_IDENTITY,() -> service.approve(id,"human-reviewer"));
        code(UNKNOWN_IDENTITY,() -> service.observe(id,SUCCESS));
        code(INVALID_EVENT,() -> service.observe(id,null));
        code(INVALID_EVENT,() -> service.suspend(id,null));
        assertTrue(store.read().isEmpty());
    }
    @Test void policiesAndReturnedSnapshotsAreImmutableAndBounded() throws Exception {
        for(int value:new int[]{0,101}) code(INVALID_POLICY,() -> new ApprovalPolicy(value,5000));
        for(int value:new int[]{-1,10001}) code(INVALID_POLICY,() -> new ApprovalPolicy(5,value));
        assertEquals(new ApprovalPolicy(5,5000),ApprovalPolicy.defaults());
        var store=new InMemoryGovernanceStore(); var service=service(store);
        var state=service.register(TENANT,fixture()); var snapshot=store.read();
        assertThrows(UnsupportedOperationException.class,() -> snapshot.clear());
        service.observe(state.identity(),SUCCESS);
        assertEquals(1,snapshot.size()); assertEquals(Reliability.empty(),state.reliability());
        var states=GovernanceHistory.validate(store.read());
        assertThrows(UnsupportedOperationException.class,states::clear);
    }
    @Test void stricterHostPolicyPreservesHistoricalApproval() throws Exception {
        var store=new InMemoryGovernanceStore(); var service=service(store);
        var id=service.register(TENANT,fixture()).identity(); five(service,id); service.approve(id,"human-reviewer");
        var strict=new ApprovalService(store,new ApprovalPolicy(10,9000),CLOCK);
        assertEquals(APPROVED,strict.state(id).lifecycle());
        assertEquals(ApprovalCriteria.from(ApprovalPolicy.defaults()),strict.state(id).approvedUnder());
    }
}
