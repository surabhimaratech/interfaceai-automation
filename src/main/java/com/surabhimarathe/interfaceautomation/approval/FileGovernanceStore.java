package com.surabhimarathe.interfaceautomation.approval;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.regex.Pattern;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

/** Opt-in, trusted single-process journal. No inter-process lock or overwrite fallback. */
public final class FileGovernanceStore implements GovernanceStore {
    private static final Map<Path,Lock> LOCKS = new ConcurrentHashMap<>();
    private static final Pattern NAME = Pattern.compile("event-([0-9]{10})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json");
    private static final class Lock { boolean mutating; }
    @FunctionalInterface interface Publisher { void link(Path target, Path temporary) throws IOException; }
    private final Path directory;
    private final Supplier<UUID> uuids;
    private final Publisher publisher;
    private final Lock lock;
    private final GovernanceJson json = new GovernanceJson();
    public FileGovernanceStore(Path trustedDirectory, Supplier<UUID> uuids) {
        this(trustedDirectory,uuids,Files::createLink);
    }
    public FileGovernanceStore(Path trustedDirectory) { this(trustedDirectory,UUID::randomUUID); }
    FileGovernanceStore(Path trustedDirectory, Supplier<UUID> uuids, Publisher publisher) {
        if (trustedDirectory == null || uuids == null || publisher == null) throw fail(Code.STORAGE_FAILURE);
        try {
            Files.createDirectories(trustedDirectory);
            directory = trustedDirectory.toRealPath();
            this.uuids = uuids; this.publisher = publisher;
            lock = LOCKS.computeIfAbsent(directory,p -> new Lock());
            read(); // Existing bytes must validate before the store is usable.
        } catch (ApprovalException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw fail(Code.STORAGE_FAILURE); }
    }
    @Override public List<GovernanceEvent> read() {
        synchronized (lock) { return readLocked(); }
    }
    private List<GovernanceEvent> readLocked() {
        try (var stream = Files.list(directory)) {
            var paths = stream.limit(GovernanceHistory.MAX_EVENTS+1L).sorted().toList();
            if (paths.size() > GovernanceHistory.MAX_EVENTS) throw fail(Code.CORRUPT_HISTORY);
            var history = new ArrayList<GovernanceEvent>();
            Set<UUID> ids = new HashSet<>();
            for (Path path : paths) {
                var match = NAME.matcher(path.getFileName().toString());
                if (!match.matches() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                        || Files.size(path) > 8192 || !ids.add(UUID.fromString(match.group(2))))
                    throw fail(Code.CORRUPT_HISTORY);
                var event = json.read(Files.readAllBytes(path));
                if (event.revision() != Long.parseLong(match.group(1))) throw fail(Code.CORRUPT_HISTORY);
                history.add(event);
            }
            GovernanceHistory.validate(history);
            return List.copyOf(history);
        } catch (ApprovalException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw fail(Code.CORRUPT_HISTORY); }
    }
    @Override public List<GovernanceEvent> transact(Function<List<GovernanceEvent>,GovernanceEvent> mutation) {
        synchronized (lock) {
            if (mutation == null || lock.mutating) throw fail(Code.INVALID_EVENT);
            lock.mutating = true;
            try {
                var history = readLocked();
                var event = mutation.apply(history);
                var updated = GovernanceHistory.append(history,event);
                publish(event);
                return updated;
            } finally { lock.mutating = false; }
        }
    }
    private void publish(GovernanceEvent event) {
        Path temporary = null;
        try {
            UUID id = uuids.get();
            if (id == null) throw fail(Code.STORAGE_FAILURE);
            // A reused event UUID is a collision even when its revision would differ.
            try (var entries = Files.list(directory)) {
                String suffix = "-"+id+".json";
                if (entries.anyMatch(p -> p.getFileName().toString().endsWith(suffix))) throw fail(Code.OUTPUT_COLLISION);
            }
            Path target = directory.resolve(String.format(Locale.ROOT,"event-%010d-%s.json",event.revision(),id));
            byte[] bytes = json.write(event);
            temporary = Files.createTempFile(directory,".governance-",".tmp");
            try (var channel = FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            publisher.link(target,temporary);
        } catch (FileAlreadyExistsException ex) { throw fail(Code.OUTPUT_COLLISION); }
        catch (ApprovalException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw fail(Code.STORAGE_FAILURE); }
        finally {
            if (temporary != null) {
                try { Files.delete(temporary); }
                catch (IOException ex) { throw fail(Code.STORAGE_FAILURE); }
            }
        }
    }
    @Override public String toString() { return "FileGovernanceStore[REDACTED]"; }
}
