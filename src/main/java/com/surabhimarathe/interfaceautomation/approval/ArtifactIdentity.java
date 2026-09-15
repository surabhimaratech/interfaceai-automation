package com.surabhimarathe.interfaceautomation.approval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.surabhimarathe.interfaceautomation.artifact.ArtifactJson;
import com.surabhimarathe.interfaceautomation.replay.TenantId;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

/** Exact bytes define identity. Raw tenant identity is never retained. */
public final class ArtifactIdentity {
    @JsonProperty private final String tenantScopeHash;
    @JsonProperty private final String capabilityId;
    @JsonProperty private final String targetId;
    @JsonProperty private final int schemaVersion;
    @JsonProperty private final int artifactVersion;
    @JsonProperty private final String artifactDigest;

    @JsonCreator
    private ArtifactIdentity(@JsonProperty("tenantScopeHash") String tenantScopeHash,
            @JsonProperty("capabilityId") String capabilityId, @JsonProperty("targetId") String targetId,
            @JsonProperty("schemaVersion") int schemaVersion, @JsonProperty("artifactVersion") int artifactVersion,
            @JsonProperty("artifactDigest") String artifactDigest) {
        if (!Hashes.digest(tenantScopeHash) || !Hashes.digest(artifactDigest)
                || !identifier(capabilityId) || !identifier(targetId) || schemaVersion != 1 || artifactVersion < 1)
            throw fail(Code.INVALID_IDENTITY);
        this.tenantScopeHash = tenantScopeHash; this.capabilityId = capabilityId; this.targetId = targetId;
        this.schemaVersion = schemaVersion; this.artifactVersion = artifactVersion; this.artifactDigest = artifactDigest;
    }
    private static boolean identifier(String s) { return s != null && s.matches("[A-Za-z][A-Za-z0-9_-]{0,79}"); }

    public static ArtifactIdentity from(TenantId tenant, byte[] utf8Json) {
        if (tenant == null || utf8Json == null || utf8Json.length > 512_000) throw fail(Code.INVALID_IDENTITY);
        byte[] bytes = utf8Json.clone();
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var artifact = new ArtifactJson().read(json);
            // Do not let artifact identifiers copy raw tenant scope into stored metadata.
            String scope = tenant.value().toLowerCase(Locale.ROOT);
            if (artifact.capabilityId().toLowerCase(Locale.ROOT).contains(scope)
                    || artifact.target().targetId().toLowerCase(Locale.ROOT).contains(scope))
                throw fail(Code.INVALID_IDENTITY);
            return new ArtifactIdentity(Hashes.scoped("interfaceai:approval:tenant:v1", tenant.value()),
                    artifact.capabilityId(), artifact.target().targetId(), artifact.schemaVersion(),
                    artifact.artifactVersion(), Hashes.sha256(bytes));
        } catch (ApprovalException ex) { throw ex; }
        catch (Exception ex) { throw fail(Code.INVALID_ARTIFACT); }
    }
    public String tenantScopeHash() { return tenantScopeHash; }
    public String capabilityId() { return capabilityId; }
    public String targetId() { return targetId; }
    public int schemaVersion() { return schemaVersion; }
    public int artifactVersion() { return artifactVersion; }
    public String artifactDigest() { return artifactDigest; }
    @Override public boolean equals(Object o) {
        return o instanceof ArtifactIdentity i && schemaVersion == i.schemaVersion && artifactVersion == i.artifactVersion
                && tenantScopeHash.equals(i.tenantScopeHash) && capabilityId.equals(i.capabilityId)
                && targetId.equals(i.targetId) && artifactDigest.equals(i.artifactDigest);
    }
    @Override public int hashCode() { return Objects.hash(tenantScopeHash,capabilityId,targetId,schemaVersion,artifactVersion,artifactDigest); }
    @Override public String toString() { return "ArtifactIdentity[REDACTED]"; }
}
