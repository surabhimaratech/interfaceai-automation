package com.surabhimarathe.interfaceautomation.artifact;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;

/** Explicit host opt-in. A hard-link publish is atomic and fails if the final name already exists. */
public final class ArtifactStore {
    private final Path directory;
    public ArtifactStore(Path trustedDirectory) { directory = trustedDirectory.toAbsolutePath().normalize(); }
    public void persist(CapabilityArtifact artifact) throws IOException {
        String json = new ArtifactJson().write(artifact);
        if (artifact.provenance().source() != Provenance.Source.COMPILED_TRACE)
            throw ArtifactValidationException.at(ValidationCode.INVALID_PROVENANCE, "provenance");
        Files.createDirectories(directory);
        Path real = directory.toRealPath();
        Path target = real.resolve("capability-" + artifact.provenance().traceId() + ".json");
        Path temp = Files.createTempFile(real, ".artifact-", ".tmp");
        try {
            try (var channel = FileChannel.open(temp, Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                var buffer = StandardCharsets.UTF_8.encode(json);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Unlike ATOMIC_MOVE, createLink cannot replace a concurrently created destination.
            Files.createLink(target, temp);
        } finally { Files.deleteIfExists(temp); }
    }
}
