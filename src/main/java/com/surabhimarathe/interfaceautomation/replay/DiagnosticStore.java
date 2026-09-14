package com.surabhimarathe.interfaceautomation.replay;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Host-only opt-in; publishes sanitized diagnostics, never ReplayResult or browser data. */
public final class DiagnosticStore {
    private final Path directory;
    private final Supplier<UUID> identifiers;
    public DiagnosticStore(Path trustedDirectory) { this(trustedDirectory, UUID::randomUUID); }
    DiagnosticStore(Path trustedDirectory, Supplier<UUID> identifiers) {
        directory = trustedDirectory.toAbsolutePath().normalize();
        this.identifiers = identifiers;
    }
    // Only the replay package's sanitized result boundary can publish.
    void persist(ReplayDiagnostic diagnostic) throws IOException {
        Objects.requireNonNull(diagnostic);
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(diagnostic);
        UUID id = Objects.requireNonNull(identifiers.get());
        try { Files.createDirectories(directory); }
        catch (FileAlreadyExistsException ex) { throw new IOException("DIAGNOSTIC_DIRECTORY_UNAVAILABLE"); }
        Path real = directory.toRealPath();
        Path target = real.resolve("diagnostic-" + id + ".json");
        Path temporary = Files.createTempFile(real, ".diagnostic-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var bytes = StandardCharsets.UTF_8.encode(json);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.createLink(target, temporary); // Atomic create-new; never fall back to overwrite.
        } finally { Files.deleteIfExists(temporary); }
    }
}
