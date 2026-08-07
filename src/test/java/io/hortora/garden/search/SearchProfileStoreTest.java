package io.hortora.garden.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProfileStoreTest {

    private final SearchProfileStore store = new SearchProfileStore();

    @BeforeEach
    void init() {
        store.init();
        store.delete("test-project");
        store.delete("proj");
        store.delete("temp");
        store.delete("a");
        store.delete("b");
    }

    @Test
    void putAndGet() {
        store.put("test-project", "quarkus:3.36.1|jdk:26.0.2");
        Optional<Map<String, String>> bom = store.get("test-project");
        assertThat(bom).isPresent();
        assertThat(bom.get()).containsEntry("quarkus", "3.36.1");
        assertThat(bom.get()).containsEntry("jdk", "26.0.2");
    }

    @Test
    void getMissingReturnsEmpty() {
        assertThat(store.get("nonexistent")).isEmpty();
    }

    @Test
    void putOverwrites() {
        store.put("proj", "quarkus:3.20");
        store.put("proj", "quarkus:3.36");
        assertThat(store.get("proj").get()).containsEntry("quarkus", "3.36");
    }

    @Test
    void deleteRemoves() {
        store.put("temp", "jdk:21");
        assertThat(store.delete("temp")).isTrue();
        assertThat(store.get("temp")).isEmpty();
    }

    @Test
    void listReturnsNames() {
        store.put("a", "jdk:21");
        store.put("b", "jdk:26");
        List<String> names = store.list();
        assertThat(names).contains("a", "b");
    }

    @Test
    void parseStackHandlesEmptyAndNull() {
        assertThat(SearchProfileStore.parseStack(null)).isEmpty();
        assertThat(SearchProfileStore.parseStack("")).isEmpty();
        assertThat(SearchProfileStore.parseStack("  ")).isEmpty();
    }

    @Test
    void parseStackHandlesMultipleEntries() {
        Map<String, String> bom = SearchProfileStore.parseStack("quarkus:3.36.1|jdk:26.0.2|onnx-runtime:1.26.0");
        assertThat(bom).hasSize(3);
        assertThat(bom).containsEntry("onnx-runtime", "1.26.0");
    }
}
