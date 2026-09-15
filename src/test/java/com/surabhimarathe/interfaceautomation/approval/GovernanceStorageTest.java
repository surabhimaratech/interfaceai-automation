package com.surabhimarathe.interfaceautomation.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalDomainTest.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.Code.*;
import static com.surabhimarathe.interfaceautomation.approval.ReliabilityObservation.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalState.Lifecycle.*;

class GovernanceStorageTest {
    @TempDir Path directory;
    Supplier<UUID> ids() { var counter=new AtomicLong(); return () -> new UUID(0,counter.incrementAndGet()); }
    List<Path> files() throws IOException { try(var stream=Files.list(directory)) { return stream.sorted().toList(); } }
    GovernanceEvent registration() throws Exception {
        return new GovernanceEvent(1,ArtifactIdentity.from(TENANT,fixture()),GovernanceEvent.Type.REGISTER,
                null,null,1,CLOCK.instant(),null,null);
    }
    void write(String name, GovernanceEvent event) throws Exception { Files.write(directory.resolve(name),new GovernanceJson().write(event)); }
    String filename(long revision,long uuid) { return String.format(Locale.ROOT,"event-%010d-%s.json",revision,new UUID(0,uuid)); }

    @Test void fileRoundTripStoresOnlyGovernanceMetadataAndNoRawIdentities() throws Exception {
        var store=new FileGovernanceStore(directory,ids()); var service=service(store);
        var id=service.register(TENANT,fixture()).identity(); five(service,id);
        var approved=service.approve(id,"private-approver");
        var reopened=service(new FileGovernanceStore(directory,ids()));
        assertEquals(approved,reopened.state(id));
        assertEquals(7,files().size());
        for(Path path:files()) {
            String raw=Files.readString(path);
            assertTrue(path.getFileName().toString().matches("event-[0-9]{10}-[0-9a-f-]{36}\\.json"));
            String presentation=path.getFileName()+raw+approved+id+store+store.read();
            for(String secret:List.of(TENANT.value(),"private-approver","100042","25.00","Courtesy adjustment",
                    "http://","cookie","token","selector","ReplayResult"))
                assertFalse(presentation.contains(secret),secret);
            assertFalse(path.getFileName().toString().contains(id.artifactDigest()));
        }
        assertThrows(UnsupportedOperationException.class,() -> store.read().clear());
    }
    @Test void duplicateUuidCollisionPreservesEveryExistingByteAndNoTemporaryFiles() throws Exception {
        UUID fixed=new UUID(0,1); var store=new FileGovernanceStore(directory,() -> fixed); var service=service(store);
        var id=service.register(TENANT,fixture()).identity(); Path original=files().getFirst(); byte[] before=Files.readAllBytes(original);
        code(OUTPUT_COLLISION,() -> service.observe(id,SUCCESS));
        assertArrayEquals(before,Files.readAllBytes(original)); assertEquals(1,files().size());
        assertEquals(0,service.state(id).reliability().eligible());
    }
    @Test void atomicDestinationCollisionNeverOverwritesAndCleansTemporaryFile() throws Exception {
        var ordinary=new FileGovernanceStore(directory,ids()); var id=service(ordinary).register(TENANT,fixture()).identity();
        byte[] sentinel="existing external bytes".getBytes(StandardCharsets.UTF_8);
        var store=new FileGovernanceStore(directory,() -> new UUID(0,2),(target,temp) -> {
            Files.write(target,sentinel,StandardOpenOption.CREATE_NEW); Files.createLink(target,temp);
        });
        byte[] first=Files.readAllBytes(files().getFirst());
        code(OUTPUT_COLLISION,() -> service(store).observe(id,SUCCESS));
        assertArrayEquals(sentinel,Files.readAllBytes(directory.resolve(filename(2,2))));
        assertArrayEquals(first,Files.readAllBytes(directory.resolve(filename(1,1))));
        assertEquals(2,files().size());
        code(CORRUPT_HISTORY,store::read); // External conflicting bytes cannot be trusted afterward.
    }
    @Test void unsupportedHardLinksFailClosedWithoutFallbackOrTemporaryFiles() throws Exception {
        var store=new FileGovernanceStore(directory,ids(),(target,temp) -> { throw new UnsupportedOperationException("PRIVATE"); });
        code(STORAGE_FAILURE,() -> service(store).register(TENANT,fixture()));
        assertTrue(files().isEmpty());
    }
    @Test void malformedHistoryIsRejectedWithoutChangingItsBytes() throws Exception {
        Path path=directory.resolve(filename(1,1)); byte[] bytes="{private malformed".getBytes(StandardCharsets.UTF_8);
        Files.write(path,bytes);
        code(CORRUPT_HISTORY,() -> new FileGovernanceStore(directory));
        assertArrayEquals(bytes,Files.readAllBytes(path)); assertEquals(1,files().size());
    }
    @Test void missingDuplicateOutOfOrderAndConflictingHistoriesFailClosed() throws Exception {
        var first=registration();
        List<List<GovernanceEvent>> cases=List.of(
                List.of(new GovernanceEvent(1,first.identity(),first.type(),null,null,2,CLOCK.instant(),null,null)),
                List.of(first,first),
                List.of(first,new GovernanceEvent(1,first.identity(),first.type(),null,null,2,CLOCK.instant(),null,null)),
                List.of(first,new GovernanceEvent(1,first.identity(),GovernanceEvent.Type.OBSERVE,SUCCESS,null,2,
                        CLOCK.instant().minusSeconds(1),null,null)),
                List.of(first,new GovernanceEvent(1,first.identity(),GovernanceEvent.Type.APPROVE,null,null,2,
                        CLOCK.instant(),Hashes.scoped("actor","human"),ApprovalCriteria.from(ApprovalPolicy.defaults()))));
        for(var events:cases) code(CORRUPT_HISTORY,() -> GovernanceHistory.validate(events));
        write(filename(1,1),first); write(filename(1,2),first);
        var before=Files.readAllBytes(directory.resolve(filename(1,1)));
        code(CORRUPT_HISTORY,() -> new FileGovernanceStore(directory));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve(filename(1,1))));
    }
    @Test void fileNameRevisionUuidAndUnknownFilesAreValidated() throws Exception {
        var first=registration(); write(filename(2,1),first);
        code(CORRUPT_HISTORY,() -> new FileGovernanceStore(directory));
        Files.delete(directory.resolve(filename(2,1)));
        write(filename(1,1),first);
        write(filename(2,1),new GovernanceEvent(1,first.identity(),GovernanceEvent.Type.OBSERVE,SUCCESS,null,2,CLOCK.instant(),null,null));
        code(CORRUPT_HISTORY,() -> new FileGovernanceStore(directory));
        Files.delete(directory.resolve(filename(2,1))); Files.writeString(directory.resolve(".unfinished.tmp"),"private");
        code(CORRUPT_HISTORY,() -> new FileGovernanceStore(directory));
    }
    @Test void strictJsonRejectsUnknownDuplicateCoercedAndMalformedMetadata() throws Exception {
        var codec=new GovernanceJson(); String json=new String(codec.write(registration()),StandardCharsets.UTF_8);
        assertEquals(registration(),codec.read(json.getBytes(StandardCharsets.UTF_8)));
        String digest=registration().identity().artifactDigest();
        for(String bad:List.of(json+" {}",json.replaceFirst("\\{","{\"untrusted\":true,"),
                json.replaceFirst("\\{","{\"revision\":1,"),
                json.replace("\"revision\" : 1","\"revision\" : \"1\""),
                json.replace("\"revision\" : 1","\"revision\" : 1.5"),
                json.replace(digest,digest.toUpperCase(Locale.ROOT)),
                json.replace(digest,"a".repeat(63)),
                json.replace("\"REGISTER\"","0"),
                json.replace("\"actorHash\" : null","\"actorHash\" : \"raw-actor\""),
                json.replace("\"journalSchemaVersion\" : 1","\"journalSchemaVersion\" : 2"))) {
            assertNotEquals(json,bad);
            code(CORRUPT_HISTORY,() -> codec.read(bad.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void conflictingIdentityMetadataForSameScopeAndDigestIsRejected() throws Exception {
        var codec=new GovernanceJson(); var first=registration();
        String altered=new String(codec.write(first),StandardCharsets.UTF_8)
                .replace("\"artifactVersion\" : 1","\"artifactVersion\" : 2")
                .replace("\"revision\" : 1","\"revision\" : 2");
        var second=codec.read(altered.getBytes(StandardCharsets.UTF_8));
        code(CORRUPT_HISTORY,() -> GovernanceHistory.validate(List.of(first,second)));
    }
    @Test void concurrentInMemoryObservationsHaveNoLostEvents() throws Exception {
        var store=new InMemoryGovernanceStore(); var service=service(store);
        var id=service.register(TENANT,fixture()).identity();
        concurrent(() -> service.observe(id,SUCCESS),100);
        assertEquals(100,service.state(id).reliability().eligible()); assertEquals(101,store.read().size());
    }
    @Test void clockRegressionOrCorruptionAfterOpenCannotAppend() throws Exception {
        var store=new FileGovernanceStore(directory,ids()); var service=service(store);
        var id=service.register(TENANT,fixture()).identity();
        var earlier=new ApprovalService(store,ApprovalPolicy.defaults(),
                Clock.fixed(CLOCK.instant().minusSeconds(1),ZoneOffset.UTC));
        byte[] before=Files.readAllBytes(files().getFirst());
        code(CORRUPT_HISTORY,() -> earlier.observe(id,SUCCESS));
        assertEquals(1,files().size()); assertArrayEquals(before,Files.readAllBytes(files().getFirst()));
        Files.writeString(directory.resolve("unexpected.json"),"private corruption");
        code(CORRUPT_HISTORY,() -> service.observe(id,SUCCESS));
        assertEquals(2,files().size()); assertArrayEquals(before,Files.readAllBytes(directory.resolve(filename(1,1))));
    }
    @Test void terminalSuspensionAndInvalidDigestFieldsCannotBeRestoredAsApproval() throws Exception {
        var store=new InMemoryGovernanceStore(); var service=service(store);
        var id=service.register(TENANT,fixture()).identity(); five(service,id);
        service.approve(id,"human-reviewer"); service.suspend(id,SuspensionReason.TARGET_CHANGED);
        var history=new ArrayList<>(store.read());
        history.add(new GovernanceEvent(1,id,GovernanceEvent.Type.APPROVE,null,null,9,CLOCK.instant(),
                Hashes.scoped("interfaceai:approval:actor:v1","human-reviewer"),ApprovalCriteria.from(ApprovalPolicy.defaults())));
        code(CORRUPT_HISTORY,() -> GovernanceHistory.validate(history));
        var codec=new GovernanceJson();
        String json=new String(codec.write(store.read().get(6)),StandardCharsets.UTF_8);
        for(String digest:List.of(id.tenantScopeHash(),store.read().get(6).actorHash())) {
            String altered=json.replace(digest,digest.toUpperCase(Locale.ROOT));
            code(CORRUPT_HISTORY,() -> codec.read(altered.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void separateFileStoreInstancesShareOneInProcessMutationLock() throws Exception {
        var supplier=ids();
        var one=new FileGovernanceStore(directory,supplier); var two=new FileGovernanceStore(directory,supplier);
        var a=service(one); var b=service(two); var id=a.register(TENANT,fixture()).identity();
        var counter=new AtomicLong();
        concurrent(() -> { (counter.incrementAndGet()%2==0?a:b).observe(id,EXPECTED_OUTCOME); return null; },40);
        assertEquals(40,a.state(id).reliability().handled()); assertEquals(41,one.read().size());
        assertEquals(one.read(),two.read());
    }
    @Test void concurrentApprovalsPublishOnlyOneApproval() throws Exception {
        var store=new InMemoryGovernanceStore(); var service=service(store); var id=service.register(TENANT,fixture()).identity();
        five(service,id); var approvals=new AtomicLong(); var rejections=new AtomicLong();
        concurrent(() -> {
            try { service.approve(id,"human-reviewer"); approvals.incrementAndGet(); }
            catch(ApprovalException ex) { assertEquals(INVALID_TRANSITION,ex.code()); rejections.incrementAndGet(); }
            return null;
        },10);
        assertEquals(1,approvals.get()); assertEquals(9,rejections.get()); assertEquals(APPROVED,service.state(id).lifecycle());
    }
    static void concurrent(Callable<?> operation,int count) throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<count;i++) futures.add(pool.submit(operation));
            for(var future:futures) future.get(20,TimeUnit.SECONDS);
        }
    }
}
