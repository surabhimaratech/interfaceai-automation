package com.surabhimarathe.interfaceautomation.approval;

import com.surabhimarathe.interfaceautomation.replay.TenantId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/** Host-trusted governance administration only; no replay or observation interface. */
public final class ApprovalCli {
    private ApprovalCli() {}
    public static void main(String[] args) {
        System.out.println(execute(args, System.getenv()));
    }

    static String execute(String[] args, Map<String, String> environment) {
        if (args == null || args.length < 1 || args[0] == null
                || !Set.of("register", "status", "approve", "suspend").contains(args[0])
                || args.length != ("suspend".equals(args[0]) ? 3 : 2))
            return failure("INVALID_COMMAND");
        SuspensionReason reason = null;
        if ("suspend".equals(args[0])) {
            try { reason = SuspensionReason.valueOf(args[2]); }
            catch (RuntimeException ex) { return failure("INVALID_COMMAND"); }
        }
        Path directory;
        TenantId tenant;
        String actor;
        try {
            String configuredDirectory = environment.get("REPLAY_GOVERNANCE_DIRECTORY");
            if (configuredDirectory == null || configuredDirectory.isBlank())
                return failure("INVALID_CONFIGURATION");
            directory = Path.of(configuredDirectory);
            tenant = new TenantId(environment.get("REPLAY_TENANT_ID"));
            actor = environment.get("APPROVAL_ACTOR");
            if ("approve".equals(args[0])) ApprovalService.validateActor(actor);
        } catch (ApprovalException ex) { return failure(ex.code().name()); }
        catch (RuntimeException ex) { return failure("INVALID_CONFIGURATION"); }

        byte[] bytes;
        ArtifactIdentity identity;
        try {
            Path artifact = Path.of(args[1]);
            // Same file-size bound as ReplayCli; check again after reading against growth.
            if (Files.size(artifact) > 128_000) return failure("INVALID_ARTIFACT");
            bytes = Files.readAllBytes(artifact);
            if (bytes.length > 128_000) return failure("INVALID_ARTIFACT");
            identity = ArtifactIdentity.from(tenant, bytes);
        } catch (Exception ex) { return failure("INVALID_ARTIFACT"); }

        try {
            var policy = ApprovalPolicy.defaults();
            var service = new ApprovalService(new FileGovernanceStore(directory), policy, Clock.systemUTC());
            var state = switch (args[0]) {
                case "register" -> service.register(tenant, bytes);
                case "status" -> service.state(identity);
                case "approve" -> service.approve(identity, actor);
                case "suspend" -> service.suspend(identity, reason);
                default -> throw new IllegalStateException();
            };
            var r = state.reliability();
            String result = "ApprovalResult[code=OK, lifecycle=" + state.lifecycle()
                    + ", handled=" + r.handled() + ", eligible=" + r.eligible()
                    + ", assisted=" + r.assisted() + ", safetyFailures=" + r.safetyFailures()
                    + ", scoreBasisPoints=" + r.scoreBasisPoints()
                    + ", eligibleForApproval=" + (state.lifecycle() == ApprovalState.Lifecycle.DRAFT && policy.eligible(r));
            var criteria = state.approvedUnder();
            if (criteria != null) result += ", approvedUnder.minimumEligibleRuns=" + criteria.minimumEligibleRuns()
                    + ", approvedUnder.minimumScoreBasisPoints=" + criteria.minimumScoreBasisPoints()
                    + ", approvedUnder.requireZeroSafetyFailures=" + criteria.requireZeroSafetyFailures();
            if (state.suspensionReason() != null) result += ", suspensionReason=" + state.suspensionReason();
            return result + "]";
        } catch (ApprovalException ex) { return failure(ex.code().name()); }
        catch (RuntimeException ex) { return failure("STORAGE_FAILURE"); }
    }

    private static String failure(String fixedCode) { return "ApprovalResult[code=" + fixedCode + "]"; }
}
