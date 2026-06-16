package io.hortora.garden.index;

import com.google.common.util.concurrent.Futures;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.ChangedEntry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GardenIngestionServiceTest {

    @TempDir Path gardenDir;
    private QdrantClient qdrantClient;
    private EmbeddingModel embeddingModel;
    private GardenMetadataExtractor extractor;
    private FileCursorStore cursorStore;
    private GardenIngestionService service;

    @BeforeEach
    void setUp() {
        qdrantClient = mock(QdrantClient.class);
        embeddingModel = mock(EmbeddingModel.class);
        extractor = new GardenMetadataExtractor();
        cursorStore = new FileCursorStore(gardenDir);

        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(null));
        when(qdrantClient.deleteAsync(anyString(), any(Common.Filter.class)))
                .thenReturn(Futures.immediateFuture(null));

        float[] vector = new float[768];
        Embedding embedding = Embedding.from(vector);
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(embedding));
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(embedding)));

        service = new GardenIngestionService(
                qdrantClient, embeddingModel, extractor, cursorStore,
                gardenDir, "garden-test");
    }

    @Test
    void fullScanIngestsAllEntriesAndSavesCursor() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Test entry"
                domain: jvm
                type: gotcha
                score: 8
                tags: [java]
                submitted: "2026-06-16"
                ---
                Body text here.
                """);

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/test.md", ChangeType.ADDED)),
                "{\"jvm/test.md\":1718500000000}");

        service.ingest(changes);

        verify(qdrantClient).upsertAsync(eq("garden-test"), anyList());
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void deletionUsesPayloadFilterOnSourceDocumentId() {
        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/old.md", ChangeType.DELETED)),
                "{}");

        service.ingest(changes);

        verify(qdrantClient).deleteAsync(eq("garden-test"), any(Common.Filter.class));
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void extractionFailureSkipsFileButCursorAdvances() {
        // No file on disk — read will fail
        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/missing.md", ChangeType.ADDED)),
                "{\"jvm/missing.md\":1}");

        service.ingest(changes);

        verify(qdrantClient, never()).upsertAsync(anyString(), anyList());
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void infrastructureFailureDoesNotAdvanceCursor() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Test"
                domain: jvm
                type: gotcha
                score: 5
                tags: []
                submitted: "2026-06-16"
                ---
                Body.
                """);

        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFailedFuture(
                        new RuntimeException("Qdrant unreachable")));

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/test.md", ChangeType.ADDED)),
                "{\"jvm/test.md\":1}");

        service.ingest(changes);

        assertThat(cursorStore.load()).isEmpty();
    }

    @Test
    void emptyChangeSetSavesCursorWithoutQdrantCalls() {
        ChangeSet changes = new ChangeSet(List.of(), "{\"a.md\":1}");

        service.ingest(changes);

        verify(qdrantClient, never()).upsertAsync(anyString(), anyList());
        verify(qdrantClient, never()).deleteAsync(anyString(), any(Common.Filter.class));
        assertThat(cursorStore.load()).hasValue("{\"a.md\":1}");
    }

    @Test
    void nonMdFilesAreSkipped() throws Exception {
        writeFixture("README", "not a markdown file");

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("README", ChangeType.ADDED)),
                "{}");

        service.ingest(changes);

        verify(qdrantClient, never()).upsertAsync(anyString(), anyList());
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void tryLockContentionSkipsGracefully() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Test"
                domain: jvm
                type: gotcha
                score: 5
                tags: []
                submitted: "2026-06-16"
                ---
                Body.
                """);

        // Hold the lock from a different thread to simulate contention
        // (ReentrantLock is reentrant within the same thread)
        ReentrantLock lock = service.lockForTesting();
        var lockHeld = new java.util.concurrent.CountDownLatch(1);
        var testDone = new java.util.concurrent.CountDownLatch(1);
        Thread blocker = new Thread(() -> {
            lock.lock();
            try {
                lockHeld.countDown();
                testDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        blocker.start();
        lockHeld.await();

        try {
            ChangeSet changes = new ChangeSet(
                    List.of(new ChangedEntry("jvm/test.md", ChangeType.ADDED)),
                    "{\"jvm/test.md\":1}");

            service.ingest(changes);

            // Should skip entirely — no Qdrant calls, no cursor save
            verify(qdrantClient, never()).upsertAsync(anyString(), anyList());
            assertThat(cursorStore.load()).isEmpty();
        } finally {
            testDone.countDown();
            blocker.join();
        }
    }

    @Test
    void modifiedEntryDeletesThenReinserts() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Updated entry"
                domain: jvm
                type: gotcha
                score: 7
                tags: [java]
                submitted: "2026-06-16"
                ---
                Updated body.
                """);

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/test.md", ChangeType.MODIFIED)),
                "{\"jvm/test.md\":2}");

        service.ingest(changes);

        verify(qdrantClient).deleteAsync(eq("garden-test"), any(Common.Filter.class));
        verify(qdrantClient).upsertAsync(eq("garden-test"), anyList());
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void onChangesProcessesEntriesWithoutSavingCursor() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Watcher entry"
                domain: jvm
                type: gotcha
                score: 6
                tags: [java]
                submitted: "2026-06-16"
                ---
                Body from watcher.
                """);

        List<ChangedEntry> entries = List.of(
                new ChangedEntry("jvm/test.md", ChangeType.ADDED));

        service.onChanges(entries);

        verify(qdrantClient).upsertAsync(eq("garden-test"), anyList());
        // Watcher does not save cursor — FlatChangeSource maintains watcher state internally
        assertThat(cursorStore.load()).isEmpty();
    }

    @Test
    void pointIdIsDeterministicForSamePath() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Test"
                domain: jvm
                type: gotcha
                score: 5
                tags: []
                submitted: "2026-06-16"
                ---
                Body.
                """);

        // Capture the points passed to upsertAsync
        @SuppressWarnings("unchecked")
        var pointsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/test.md", ChangeType.ADDED)),
                "{}");

        service.ingest(changes);

        verify(qdrantClient).upsertAsync(eq("garden-test"), pointsCaptor.capture());

        // Run again with same path to verify deterministic ID
        service.ingest(new ChangeSet(
                List.of(new ChangedEntry("jvm/test.md", ChangeType.ADDED)),
                "{\"jvm/test.md\":2}"));

        verify(qdrantClient, times(2)).upsertAsync(eq("garden-test"), pointsCaptor.capture());

        @SuppressWarnings("unchecked")
        List<Points.PointStruct> firstPoints = (List<Points.PointStruct>) pointsCaptor.getAllValues().get(0);
        @SuppressWarnings("unchecked")
        List<Points.PointStruct> secondPoints = (List<Points.PointStruct>) pointsCaptor.getAllValues().get(1);

        assertThat(firstPoints).hasSize(1);
        assertThat(secondPoints).hasSize(1);
        assertThat(firstPoints.get(0).getId()).isEqualTo(secondPoints.get(0).getId());
    }

    private void writeFixture(String relativePath, String content) throws Exception {
        Path file = gardenDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
