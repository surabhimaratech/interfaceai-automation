package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.approval.*;
import com.surabhimarathe.interfaceautomation.discovery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalState.Lifecycle.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GovernedReplayIntegrationTest {
    @LocalServerPort int port;
    @TempDir Path temporary;
    final TenantId tenant = new TenantId("governedTenant");
    final ReplayOptions options = new ReplayOptions(Duration.ofSeconds(5),Duration.ofSeconds(25),true);
    final AtomicInteger launches = new AtomicInteger();
    final InMemoryGovernanceStore events = new InMemoryGovernanceStore();
    final ApprovalService service = service(events);
    ApprovalService service(GovernanceStore store) { return new ApprovalService(store,ApprovalPolicy.defaults(),Clock.systemUTC()); }
    byte[] bytes() throws Exception {
        try(var in=getClass().getResourceAsStream("/artifacts/prepare-fee-reversal-review.v1.json")) { return in.readAllBytes(); }
    }
    String text() throws Exception { return new String(bytes(),StandardCharsets.UTF_8); }
    InvocationParameters parameters() { return new InvocationParameters(FeeReviewCapability.parameters(ScriptedCompilationScenario.request())); }
    TenantTargetRegistry targets(boolean permitSearch) throws Exception {
        var policy = ScriptedCompilationScenario.policy("http://localhost:"+port);
        if (!permitSearch) policy = new ActionPolicy(policy.origin(),List.of(java.util.regex.Pattern.compile("/legacy.*")),
                Set.of(UiAction.Type.FILL,UiAction.Type.CLICK),
                Map.of(UiAction.Type.FILL,Set.of("Member ID","Amount","Reason"),UiAction.Type.CLICK,Set.of("Open")));
        var config = new TenantTargetConfiguration(policy,null,DiagnosticRedactionPolicy.baseline());
        return new TenantTargetRegistry(Map.of(tenant,Map.of("legacy-banking",config),
                new TenantId("otherTenant"),Map.of("legacy-banking",config)));
    }
    ReplayEngine engine(ApprovalService service,boolean actual,boolean permitSearch) throws Exception {
        return new ReplayEngine(targets(permitSearch),options,(p,o) -> {
            launches.incrementAndGet();
            if (!actual) throw new IllegalStateException("PRIVATE_BROWSER");
            return p.chromium().launch(o);
        },new DiagnosticStore(temporary.resolve("diagnostics")),null,service == null?null:new ReplayGovernance(service));
    }
    ArtifactIdentity approve() throws Exception {
        var id=service.register(tenant,bytes()).identity();
        for(int i=0;i<5;i++) service.observe(id,ReliabilityObservation.SUCCESS);
        service.approve(id,"private-actor"); return id;
    }
    void fixed(ReplayResult result,ReplayResult.Code code) throws Exception {
        assertEquals(code,result.code(),result.toString());
        assertTrue(result.outputs().isEmpty());
        assertNotNull(result.diagnostic());
        String presentation=result.toString()+result.diagnostic();
        for(String secret:List.of(tenant.value(),"private-actor","100042","Courtesy adjustment","PRIVATE_BROWSER","http://"))
            assertFalse(presentation.contains(secret));
    }
    @Test void unknownDraftUnhealthyAndSuspendedNeverLaunch() throws Exception {
        var engine=engine(service,false,true);
        fixed(engine.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        var id=service.register(tenant,bytes()).identity();
        fixed(engine.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        for(int i=0;i<5;i++) service.observe(id,ReliabilityObservation.SUCCESS);
        service.approve(id,"private-actor");
        service.observe(id,ReliabilityObservation.RECOVERABLE_FAILURE); // 5/6 falls below stored 5000.
        fixed(engine.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_SUSPENDED);
        service.suspend(id,SuspensionReason.RELIABILITY_REGRESSION);
        fixed(engine.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_SUSPENDED);
        assertEquals(0,launches.get());
        assertEquals(9,events.read().size()); // No observations for denied unattended preflight.
    }
    ReplayEngine entryDeniedEngine() {
        var policy=new ActionPolicy("http://localhost:"+port,List.of(),Set.of(),Map.of());
        var config=new TenantTargetConfiguration(policy,null,DiagnosticRedactionPolicy.baseline());
        var registry=new TenantTargetRegistry(Map.of(tenant,Map.of("legacy-banking",config)));
        return new ReplayEngine(registry,options,(p,o) -> {
            launches.incrementAndGet(); throw new AssertionError("Browser must not launch");
        },null,null,new ReplayGovernance(service));
    }
    @Test void approvedEntryDenialRecordsOnePolicyBlockAndSuspendsBeforeAnyBrowserLaunch() throws Exception {
        var id=approve(); var replay=entryDeniedEngine();
        int before=events.read().size();
        var result=replay.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED);
        fixed(result,POLICY_DENIED);
        assertEquals(ReplayResult.Status.BLOCKED,result.status());
        assertEquals(0,result.step()); assertEquals(0,launches.get());
        var added=events.read().subList(before,events.read().size());
        assertEquals(2,added.size());
        assertEquals(GovernanceEvent.Type.OBSERVE,added.get(0).type());
        assertEquals(ReliabilityObservation.POLICY_BLOCK,added.get(0).observation());
        assertEquals(GovernanceEvent.Type.SUSPEND,added.get(1).type());
        assertEquals(SuspensionReason.SAFETY_REVIEW,added.get(1).suspensionReason());
        assertEquals(SUSPENDED,service.state(id).lifecycle());
        fixed(replay.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_SUSPENDED);
        assertEquals(before+2,events.read().size()); assertEquals(0,launches.get());
    }
    @Test void entryDenialDoesNotCountUnknownDraftOrInvalidPreflightAttempts() throws Exception {
        var replay=entryDeniedEngine();
        fixed(replay.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        assertTrue(events.read().isEmpty());
        var id=service.register(tenant,bytes()).identity();
        fixed(replay.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        for(int i=0;i<5;i++) service.observe(id,ReliabilityObservation.SUCCESS);
        service.approve(id,"private-actor");
        int before=events.read().size();
        fixed(replay.run(tenant,text()+" ",parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        fixed(replay.run(new TenantId("unknownTenant"),bytes(),parameters(),ReplayMode.UNATTENDED),UNKNOWN_TENANT);
        fixed(replay.run(tenant,text().replace("legacy-banking","missing-target"),parameters(),ReplayMode.UNATTENDED),UNKNOWN_TENANT_TARGET);
        fixed(replay.run(tenant,bytes(),new InvocationParameters(Map.of()),ReplayMode.UNATTENDED),INVALID_PARAMETERS);
        fixed(replay.run(tenant,new byte[]{(byte)0xC3,0x28},parameters(),ReplayMode.UNATTENDED),INVALID_ARTIFACT);
        assertEquals(before,events.read().size());
        assertEquals(APPROVED,service.state(id).lifecycle()); assertEquals(0,launches.get());
    }
    @Test void approvedExactBytesRunButChangedBytesTenantAndModeLessOverloadsDoNot() throws Exception {
        var id=approve(); var engine=engine(service,true,true);
        var result=engine.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED);
        assertEquals(CHECKPOINT_VERIFIED,result.code()); assertEquals(7,result.outputs().size()); assertEquals(1,launches.get());
        assertEquals(6,service.state(id).reliability().handled());
        fixed(engine.run(tenant,(text()+" ").getBytes(StandardCharsets.UTF_8),parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        fixed(engine.run(tenant,text()+" ",parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        fixed(engine.run(new TenantId("otherTenant"),bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        fixed(engine.run(tenant,text(),parameters()),INVALID_PARAMETERS);
        var compat=new ReplayEngine(TargetRegistry.singleTenant(tenant,Map.of("legacy-banking",
                ScriptedCompilationScenario.policy("http://localhost:"+port))),options,null,null,new ReplayGovernance(service));
        fixed(compat.run(text(),parameters()),INVALID_PARAMETERS);
        fixed(compat.run(text()+" ",parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        fixed(compat.run((text()+" ").getBytes(StandardCharsets.UTF_8),parameters(),ReplayMode.UNATTENDED),APPROVAL_REQUIRED);
        assertEquals(1,launches.get());
    }
    @Test void validationRecordsExactlyOneDerivedObservationAndNeverRegistersUnknownIdentity() throws Exception {
        var id=service.register(tenant,bytes()).identity(); var engine=engine(service,true,true);
        assertEquals(CHECKPOINT_VERIFIED,engine.run(tenant,bytes(),parameters(),ReplayMode.VALIDATION).code());
        assertEquals(2,events.read().size()); assertEquals(ReliabilityObservation.SUCCESS,events.read().getLast().observation());
        var values=new HashMap<>(parameters().values()); values.put("memberId","999999");
        assertEquals("MEMBER_NOT_FOUND",engine.run(tenant,bytes(),new InvocationParameters(values),ReplayMode.VALIDATION).effectiveCode());
        assertEquals(3,events.read().size()); assertEquals(ReliabilityObservation.EXPECTED_OUTCOME,events.read().getLast().observation());
        assertEquals(2,service.state(id).reliability().handled()); assertEquals(DRAFT,service.state(id).lifecycle());
        assertEquals(CHECKPOINT_VERIFIED,engine.run(tenant,text()+" ",parameters(),ReplayMode.VALIDATION).code());
        assertEquals(3,events.read().size());
    }
    @Test void policyAndHardFailuresAfterApprovalPersistSuspensionAndBlockNextLaunch() throws Exception {
        var id=approve(); var engine=engine(service,true,false);
        fixed(engine.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),POLICY_DENIED);
        assertEquals(SUSPENDED,service.state(id).lifecycle());
        assertEquals(ReliabilityObservation.POLICY_BLOCK,events.read().get(events.read().size()-2).observation());
        assertEquals(GovernanceEvent.Type.SUSPEND,events.read().getLast().type());
        int count=launches.get();
        fixed(engine.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_SUSPENDED);
        assertEquals(count,launches.get());
        var store=new InMemoryGovernanceStore(); var separate=service(store);
        var fresh=separate.register(tenant,bytes()).identity();
        for(int i=0;i<5;i++) separate.observe(fresh,ReliabilityObservation.SUCCESS);
        separate.approve(fresh,"private-actor");
        fixed(engine(separate,false,true).run(tenant,bytes(),parameters(),ReplayMode.VALIDATION),BROWSER_FAILURE);
        assertEquals(SUSPENDED,separate.state(fresh).lifecycle());
        assertEquals(ReliabilityObservation.HARD_FAILURE,store.read().get(store.read().size()-2).observation());
    }
    @Test void historicalApprovalUsesStoredCriteriaDespiteStricterHostPolicy() throws Exception {
        var id=approve();
        var strict=new ApprovalService(events,new ApprovalPolicy(10,9000),Clock.systemUTC());
        assertEquals(CHECKPOINT_VERIFIED,engine(strict,true,true).run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED).code());
        assertEquals(APPROVED,strict.state(id).lifecycle());
        assertEquals(new ApprovalCriteria(5,5000,true),strict.state(id).approvedUnder());
    }
    @Test void everyDispositionAndAssistedSuccessMapWithoutCallerObservationInput() {
        var cases=List.of(new ReplayResult(ReplayResult.Status.SUCCEEDED,CHECKPOINT_VERIFIED,null,8,Map.of()),
                new ReplayResult(ReplayResult.Status.EXPECTED_OUTCOME,BUSINESS_OUTCOME,"MEMBER_NOT_FOUND",2,Map.of()),
                ReplayResult.failure(TIMEOUT,1),ReplayResult.failure(POLICY_DENIED,1),ReplayResult.failure(BROWSER_FAILURE,1));
        var expected=List.of(ReliabilityObservation.SUCCESS,ReliabilityObservation.EXPECTED_OUTCOME,
                ReliabilityObservation.RECOVERABLE_FAILURE,ReliabilityObservation.POLICY_BLOCK,ReliabilityObservation.HARD_FAILURE);
        for(int i=0;i<cases.size();i++) {
            assertEquals(expected.get(i),ReplayGovernance.observation(cases.get(i)));
            assertEquals(ReliabilityObservation.HUMAN_ASSISTED,ReplayGovernance.observation(cases.get(i).withHandoffCount(1)));
        }
    }
    @Test void corruptionUnavailableStorageAndMissingGovernanceFailClosed() throws Exception {
        Path journal=temporary.resolve("journal"); var fs=new FileGovernanceStore(journal);
        var governed=service(fs); governed.register(tenant,bytes());
        Files.writeString(journal.resolve("bad.json"),"PRIVATE_CORRUPTION");
        fixed(engine(governed,false,true).run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),GOVERNANCE_FAILURE);
        var failing=new FailingStore(events); failing.failRead=true;
        fixed(engine(service(failing),false,true).run(tenant,bytes(),parameters(),ReplayMode.VALIDATION),GOVERNANCE_FAILURE);
        fixed(engine(null,false,true).run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),GOVERNANCE_FAILURE);
        assertEquals(0,launches.get());
    }
    @Test void postExecutionObservationFailureIsGovernanceFailureWithoutUndoingExecution() throws Exception {
        var id=service.register(tenant,bytes()).identity(); var failing=new FailingStore(events);
        failing.failType=GovernanceEvent.Type.OBSERVE;
        var result=engine(service(failing),true,true).run(tenant,bytes(),parameters(),ReplayMode.VALIDATION);
        fixed(result,GOVERNANCE_FAILURE); assertEquals(8,result.step()); assertEquals(1,launches.get());
        assertEquals(0,service.state(id).reliability().eligible());
    }
    @Test void failedSuspensionPublicationLeavesUnhealthyApprovalBlockedByPreflight() throws Exception {
        var id=approve(); var failing=new FailingStore(events); failing.failType=GovernanceEvent.Type.SUSPEND;
        var governed=service(failing);
        fixed(engine(governed,false,true).run(tenant,bytes(),parameters(),ReplayMode.VALIDATION),GOVERNANCE_FAILURE);
        assertEquals(APPROVED,service.state(id).lifecycle()); assertEquals(1,service.state(id).reliability().safetyFailures());
        int count=launches.get();
        fixed(engine(governed,false,true).run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_SUSPENDED);
        assertEquals(count,launches.get());
    }
    @Test void recoverableRegressionSuspendsUsingStoredWilsonThreshold() throws Exception {
        var id=approve();
        var replay=new ReplayEngine(targets(true),options,(p,o) -> {
            launches.incrementAndGet(); throw new com.microsoft.playwright.TimeoutError("PRIVATE_TIMEOUT");
        },null,null,new ReplayGovernance(service));
        fixed(replay.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),TIMEOUT);
        assertEquals(SUSPENDED,service.state(id).lifecycle());
        assertEquals(SuspensionReason.RELIABILITY_REGRESSION,events.read().getLast().suspensionReason());
        assertEquals(0,service.state(id).reliability().safetyFailures());
        assertEquals(ReliabilityObservation.RECOVERABLE_FAILURE,events.read().get(events.read().size()-2).observation());
        fixed(replay.run(tenant,bytes(),parameters(),ReplayMode.UNATTENDED),APPROVAL_SUSPENDED);
        assertEquals(1,launches.get());
    }
    @Test void persistedReplayObservationContainsOnlyGovernanceMetadata() throws Exception {
        Path journal=temporary.resolve("journal"); var store=new FileGovernanceStore(journal);
        var governed=service(store); governed.register(tenant,bytes());
        var values=new HashMap<>(parameters().values()); values.put("memberId","999999");
        var result=engine(governed,true,true).run(tenant,bytes(),new InvocationParameters(values),ReplayMode.VALIDATION);
        assertEquals("MEMBER_NOT_FOUND",result.effectiveCode());
        assertEquals(2,store.read().size());
        assertEquals(ReliabilityObservation.EXPECTED_OUTCOME,store.read().getLast().observation());
        try(var files=Files.list(journal)) {
            for(var file:files.toList()) {
                String persisted=file.getFileName()+Files.readString(file);
                for(String secret:List.of(tenant.value(),"private-actor","999999","100042","25.00","Courtesy adjustment",
                        "Morgan Lee","SAV-2048","http://","outputs","diagnostic","Member not found"))
                    assertFalse(persisted.contains(secret));
            }
        }
    }
    @Test void malformedUtf8AndInvalidInvocationFailBeforeLaunchAndGovernanceIdentifiersStayPrivate() throws Exception {
        approve(); var engine=engine(service,false,true);
        fixed(engine.run(tenant,new byte[]{(byte)0xC3,0x28},parameters(),ReplayMode.UNATTENDED),INVALID_ARTIFACT);
        fixed(engine.run(tenant,"\uD800",parameters(),ReplayMode.UNATTENDED),INVALID_ARTIFACT);
        fixed(engine.run(tenant,bytes(),new InvocationParameters(Map.of()),ReplayMode.UNATTENDED),INVALID_PARAMETERS);
        fixed(engine.run(tenant,bytes(),parameters(),null),INVALID_PARAMETERS);
        assertEquals(0,launches.get());
        try(var files=Files.list(temporary.resolve("diagnostics"))) {
            for(var file:files.toList()) for(String secret:List.of(tenant.value(),"private-actor","100042","http://","PRIVATE"))
                assertFalse((file.getFileName()+Files.readString(file)).contains(secret));
        }
    }
    static final class FailingStore implements GovernanceStore {
        final GovernanceStore delegate; boolean failRead; GovernanceEvent.Type failType;
        FailingStore(GovernanceStore delegate) { this.delegate=delegate; }
        public List<GovernanceEvent> read() {
            if(failRead) throw new IllegalStateException("PRIVATE_STORAGE");
            return delegate.read();
        }
        public List<GovernanceEvent> transact(Function<List<GovernanceEvent>,GovernanceEvent> mutation) {
            return delegate.transact(h -> {
                var next=mutation.apply(h);
                if(next.type()==failType) throw new IllegalStateException("PRIVATE_STORAGE");
                return next;
            });
        }
    }
}
