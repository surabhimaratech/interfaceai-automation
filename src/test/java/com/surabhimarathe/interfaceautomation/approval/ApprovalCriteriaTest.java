package com.surabhimarathe.interfaceautomation.approval;

import com.surabhimarathe.interfaceautomation.replay.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalDomainTest.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.Code.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalState.Lifecycle.*;
import static com.surabhimarathe.interfaceautomation.approval.ReliabilityObservation.*;

class ApprovalCriteriaTest {
    @TempDir Path directory;
    final ApprovalCriteria defaults = ApprovalCriteria.from(ApprovalPolicy.defaults());
    final GovernanceJson codec = new GovernanceJson();
    final ObjectMapper mapper = new ObjectMapper();

    @Test void stricterPolicyPreservesHistoricalApprovalAndAllowsUnrelatedWorkAndExplicitSuspension() throws Exception {
        var store = new FileGovernanceStore(directory);
        var original = service(store);
        var id = original.register(TENANT,fixture()).identity();
        assertNull(original.state(id).approvedUnder());
        five(original,id);
        var approved = original.approve(id,"private-reviewer");
        assertEquals(defaults,approved.approvedUnder());
        assertEquals(approved,service(new FileGovernanceStore(directory)).state(id));
        var strictPolicy = new ApprovalPolicy(10,9000);
        var strict = new ApprovalService(new FileGovernanceStore(directory),strictPolicy,CLOCK);
        assertEquals(approved,strict.state(id));
        var other = strict.register(new TenantId("unrelatedTenant"),fixture()).identity();
        five(strict,other);
        assertFalse(strict.eligible(other));
        code(NOT_ELIGIBLE,() -> strict.approve(other,"private-reviewer"));
        five(strict,other); // Meets count, but not the stricter Wilson score.
        assertFalse(strict.eligible(other));
        code(NOT_ELIGIBLE,() -> strict.approve(other,"private-reviewer"));
        for(int i=10;i<40;i++) strict.observe(other,SUCCESS);
        assertTrue(strict.eligible(other));
        assertEquals(ApprovalCriteria.from(strictPolicy),strict.approve(other,"private-reviewer").approvedUnder());
        strict.observe(id,HARD_FAILURE);
        assertEquals(defaults,strict.state(id).approvedUnder());
        var suspended = strict.suspend(id,SuspensionReason.OPERATOR_WITHDRAWAL);
        assertEquals(SUSPENDED,suspended.lifecycle());
        assertEquals(defaults,suspended.approvedUnder());
        assertEquals(approved.approvedAt(),suspended.approvedAt());
        assertEquals(suspended,service(new FileGovernanceStore(directory)).state(id));
        assertEquals(defaults,store.read().stream().filter(e -> e.type()==GovernanceEvent.Type.APPROVE
                && e.identity().equals(id)).findFirst().orElseThrow().approvalCriteria());
    }

    @Test void historyIntrinsicallyRejectsUnsatisfiedCountScoreOrSafetyCriteria() throws Exception {
        var store = new InMemoryGovernanceStore(); var service = service(store);
        var id = service.register(TENANT,fixture()).identity(); five(service,id);
        for(var criteria:List.of(new ApprovalCriteria(6,0,true),new ApprovalCriteria(5,9000,true))) {
            var history = new ArrayList<>(store.read());
            history.add(approval(id,history.size()+1L,criteria));
            code(CORRUPT_HISTORY,() -> GovernanceHistory.validate(history));
        }
        service.observe(id,POLICY_BLOCK);
        var history = new ArrayList<>(store.read());
        history.add(approval(id,history.size()+1L,new ApprovalCriteria(1,0,true)));
        code(CORRUPT_HISTORY,() -> GovernanceHistory.validate(history));
    }

    @Test void approveJsonHasExactSnapshotAndNoRawIdentitiesOrReplayData() throws Exception {
        var store = new FileGovernanceStore(directory); var service = service(store);
        var id = service.register(TENANT,fixture()).identity(); five(service,id);
        var state = service.approve(id,"private-reviewer");
        var event = store.read().getLast();
        var node = (ObjectNode)mapper.readTree(codec.write(event));
        assertEquals(mapper.readTree("{\"minimumEligibleRuns\":5,\"minimumScoreBasisPoints\":5000,\"requireZeroSafetyFailures\":true}"),
                node.get("approvalCriteria"));
        assertEquals(event,codec.read(codec.write(event)));
        try(var paths = Files.list(directory)) {
            for(var path:paths.toList()) {
                String data = path.getFileName()+Files.readString(path)+event+state+state.approvedUnder();
                for(String secret:List.of(TENANT.value(),"private-reviewer","ReplayResult","100042","Courtesy adjustment","http://"))
                    assertFalse(data.contains(secret));
            }
        }
    }

    @Test void strictJsonRejectsMissingExtraInvalidOrCoercedPolicyFields() throws Exception {
        var id = ArtifactIdentity.from(TENANT,fixture());
        byte[] valid = codec.write(approval(id,1,defaults));
        List<Consumer<ObjectNode>> corruptions = List.of(
                n -> n.remove("approvalCriteria"),
                n -> n.putNull("approvalCriteria"),
                n -> n.put("approvalCriteria",true),
                n -> criteria(n).remove("minimumEligibleRuns"),
                n -> criteria(n).remove("minimumScoreBasisPoints"),
                n -> criteria(n).remove("requireZeroSafetyFailures"),
                n -> criteria(n).put("minimumEligibleRuns",0),
                n -> criteria(n).put("minimumEligibleRuns",101),
                n -> criteria(n).put("minimumScoreBasisPoints",-1),
                n -> criteria(n).put("minimumScoreBasisPoints",10001),
                n -> criteria(n).put("requireZeroSafetyFailures",false),
                n -> criteria(n).put("extra","private-reviewer"),
                n -> criteria(n).put("minimumEligibleRuns","5"),
                n -> criteria(n).put("minimumEligibleRuns",5.0),
                n -> criteria(n).put("minimumScoreBasisPoints","5000"),
                n -> criteria(n).put("requireZeroSafetyFailures","true"),
                n -> criteria(n).put("requireZeroSafetyFailures",1),
                n -> criteria(n).putNull("requireZeroSafetyFailures"));
        for(var corruption:corruptions) {
            var node = (ObjectNode)mapper.readTree(valid); corruption.accept(node);
            code(CORRUPT_HISTORY,() -> codec.read(node.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test void conditionalEventFieldsAreStrictForEveryType() throws Exception {
        var id = ArtifactIdentity.from(TENANT,fixture());
        for(var type:GovernanceEvent.Type.values()) {
            var valid = new GovernanceEvent(1,id,type,type==GovernanceEvent.Type.OBSERVE?SUCCESS:null,
                    type==GovernanceEvent.Type.SUSPEND?SuspensionReason.SAFETY_REVIEW:null,1,CLOCK.instant(),
                    type==GovernanceEvent.Type.APPROVE?Hashes.scoped("actor","private-reviewer"):null,
                    type==GovernanceEvent.Type.APPROVE?defaults:null);
            assertEquals(valid,codec.read(codec.write(valid)));
            for(String field:List.of("observation","suspensionReason","actorHash","approvalCriteria")) {
                var node = (ObjectNode)mapper.readTree(codec.write(valid));
                if (!node.get(field).isNull()) node.putNull(field);
                else switch(field) {
                    case "observation" -> node.put(field,"SUCCESS");
                    case "suspensionReason" -> node.put(field,"SAFETY_REVIEW");
                    case "actorHash" -> node.put(field,Hashes.scoped("actor","private-reviewer"));
                    case "approvalCriteria" -> node.set(field,mapper.valueToTree(defaults));
                }
                code(CORRUPT_HISTORY,() -> codec.read(node.toString().getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Test void stateCriteriaAreMandatoryOnlyAfterApprovalAndRemainImmutable() throws Exception {
        var id = ArtifactIdentity.from(TENANT,fixture());
        code(INVALID_EVENT,() -> new ApprovalState(id,DRAFT,Reliability.empty(),null,null,null,defaults));
        code(INVALID_EVENT,() -> new ApprovalState(id,APPROVED,Reliability.empty(),CLOCK.instant(),
                Hashes.scoped("actor","private-reviewer"),null,null));
        code(INVALID_POLICY,() -> new ApprovalCriteria(5,5000,false));
        assertEquals(new ApprovalCriteria(5,5000,true),defaults);
        assertTrue(ApprovalCriteria.class.isRecord());
    }
    private ObjectNode criteria(ObjectNode node) { return (ObjectNode)node.get("approvalCriteria"); }
    private GovernanceEvent approval(ArtifactIdentity id,long revision,ApprovalCriteria criteria) {
        return new GovernanceEvent(1,id,GovernanceEvent.Type.APPROVE,null,null,revision,CLOCK.instant(),
                Hashes.scoped("actor","private-reviewer"),criteria);
    }
}
