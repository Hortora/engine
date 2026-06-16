package io.hortora.garden.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Testcontainers lifecycle manager for Qdrant.
 * Starts a fresh Qdrant container and deletes any stale cursor file
 * so the indexer performs a full scan against the empty collection.
 */
public class QdrantResource implements QuarkusTestResourceLifecycleManager {

    /** Must match the value in src/test/resources/application.properties */
    private static final String TEST_GARDEN_PATH = "src/test/resources/fixtures";

    private GenericContainer<?> qdrant;

    @Override
    public Map<String, String> start() {
        deleteStaleCursor();

        qdrant = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.14.1"))
                .withExposedPorts(6333, 6334)
                .waitingFor(Wait.forHttp("/readyz").forPort(6333).forStatusCode(200));
        qdrant.start();

        return Map.of(
                "hortora.qdrant.host", qdrant.getHost(),
                "hortora.qdrant.port", String.valueOf(qdrant.getMappedPort(6334))
        );
    }

    @Override
    public void stop() {
        if (qdrant != null) {
            qdrant.stop();
        }
    }

    /**
     * The Qdrant container is always fresh, so a stale cursor from a previous run
     * would cause changesSince() to return an empty changeset — nothing indexed.
     */
    private static void deleteStaleCursor() {
        Path cursor = Path.of(TEST_GARDEN_PATH, "_state", "garden.cursor");
        try {
            Files.deleteIfExists(cursor);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stale cursor: " + cursor, e);
        }
    }
}
