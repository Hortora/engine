# BGE-M3 Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three-model embedding stack with BGE-M3 via a new `MultiModalEmbedder` SPI, evolving `InferenceOutput` for multi-output models and adding ColBERT MAX_SIM reranking.

**Architecture:** Two-repo change. Phase A (Tasks 1-4) evolves the neural-text inference SPI layer. Phase B (Tasks 5-6) evolves the neural-text RAG pipeline. Phase C (Task 7) adapts the engine as a consumer. Strict dependency order — each phase depends on the previous.

**Tech Stack:** Java 25, Quarkus 3.36.x, ONNX Runtime, Qdrant gRPC, JUnit 5

## Global Constraints

- **Repos:** neural-text at `/Users/mdproctor/claude/casehub/neural-text/`, engine at `/Users/mdproctor/claude/hortora/engine/`
- **Cross-repo commits require explicit user approval** before each commit to neural-text from this engine session
- **TDD:** Write failing tests first, then implement. Every task follows red-green-commit.
- **Breaking changes are free:** This platform has no end users. Fix the design, don't protect callers.
- **No backward-compat shims:** `InferenceOutput.of()` factory for single-output convenience is fine; wrappers that exist solely to avoid migration are not.
- **Spec:** `docs/superpowers/specs/2026-06-30-bge-m3-adoption-design.md` — the reviewed source of truth for all design decisions.
- **Build:** `./mvnw verify` in each repo. neural-text must build green before engine tasks begin.
- **ONNX model prerequisite:** The three-head BGE-M3 ONNX export does not exist yet. Tasks 1-6 use `InMemoryInferenceModel` stubs. Task 7 engine integration tests also use stubs. End-to-end with a real model is a separate follow-up after the ONNX export script is delivered.

---

### Task 1: InferenceOutput Evolution

**Repo:** neural-text

**Files:**
- Modify: `inference-api/src/main/java/io/casehub/inference/InferenceOutput.java`
- Modify: `inference-api/src/test/java/io/casehub/inference/InferenceOutputTest.java`
- Modify: `inference-inmem/src/main/java/io/casehub/inference/inmem/InMemoryInferenceModel.java`
- Modify: `inference-inmem/src/test/java/io/casehub/inference/inmem/InMemoryInferenceModelTest.java`

**Interfaces:**
- Consumes: nothing — this is the foundation
- Produces:
  - `InferenceOutput` final class with `Map<String, float[][]> outputs` constructor
  - `InferenceOutput.of(float[] values)` — single-output factory
  - `InferenceOutput.values()` — backward-compat, throws if multi-output
  - `InferenceOutput.output(String name)` — returns `float[][]` (defensive copy)
  - `InferenceOutput.vector(String name)` — returns `output(name)[0]`
  - `InferenceOutput.outputNames()` — returns `Set<String>`
  - `InMemoryInferenceModel.returning(float...)` — uses `InferenceOutput.of()`
  - `InMemoryInferenceModel.returningMulti(Map<String, float[][]>)` — new factory for multi-output stubs

**Why final class, not record:** Record auto-generates `outputs()` accessor that leaks mutable `float[][]` arrays. Deep defensive copies on construction and access require a final class with private fields.

- [ ] **Step 1: Write InferenceOutput tests**

```java
// inference-api/src/test/java/io/casehub/inference/InferenceOutputTest.java
package io.casehub.inference;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InferenceOutputTest {

    @Test
    void singleOutputFactory() {
        InferenceOutput out = InferenceOutput.of(1f, 2f, 3f);
        assertArrayEquals(new float[]{1f, 2f, 3f}, out.values());
    }

    @Test
    void singleOutputDefensiveCopy() {
        float[] input = {1f, 2f};
        InferenceOutput out = InferenceOutput.of(input);
        input[0] = 999f;
        assertEquals(1f, out.values()[0]);
        out.values()[0] = 999f;
        assertEquals(1f, out.values()[0]);
    }

    @Test
    void multiOutputConstruction() {
        var outputs = Map.of(
            "dense", new float[][]{{1f, 2f}},
            "sparse", new float[][]{{3f, 4f, 5f}}
        );
        InferenceOutput out = new InferenceOutput(outputs);
        assertArrayEquals(new float[]{1f, 2f}, out.vector("dense"));
        assertArrayEquals(new float[]{3f, 4f, 5f}, out.vector("sparse"));
    }

    @Test
    void multiOutputDefensiveCopy() {
        float[][] data = {{1f, 2f}};
        var outputs = Map.of("a", data);
        InferenceOutput out = new InferenceOutput(outputs);
        data[0][0] = 999f;
        assertEquals(1f, out.vector("a")[0]);
    }

    @Test
    void valuesThrowsForMultiOutput() {
        var outputs = Map.of(
            "a", new float[][]{{1f}},
            "b", new float[][]{{2f}}
        );
        InferenceOutput out = new InferenceOutput(outputs);
        assertThrows(IllegalStateException.class, out::values);
    }

    @Test
    void outputThrowsForUnknownName() {
        InferenceOutput out = InferenceOutput.of(1f);
        assertThrows(IllegalArgumentException.class, () -> out.output("missing"));
    }

    @Test
    void rank2OutputPreserved() {
        float[][] colbert = {{1f, 2f}, {3f, 4f}, {5f, 6f}};
        var outputs = Map.of("colbert", colbert);
        InferenceOutput out = new InferenceOutput(outputs);
        float[][] result = out.output("colbert");
        assertEquals(3, result.length);
        assertArrayEquals(new float[]{3f, 4f}, result[1]);
    }

    @Test
    void outputNamesReturnsKeySet() {
        var outputs = Map.of(
            "dense", new float[][]{{1f}},
            "sparse", new float[][]{{2f}}
        );
        InferenceOutput out = new InferenceOutput(outputs);
        assertEquals(Set.of("dense", "sparse"), out.outputNames());
    }

    @Test
    void emptyOutputsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new InferenceOutput(Map.of()));
    }

    @Test
    void nullOutputsRejected() {
        assertThrows(NullPointerException.class,
            () -> new InferenceOutput(null));
    }

    @Test
    void equalityAndHashCode() {
        InferenceOutput a = InferenceOutput.of(1f, 2f);
        InferenceOutput b = InferenceOutput.of(1f, 2f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        InferenceOutput c = InferenceOutput.of(1f, 3f);
        assertNotEquals(a, c);
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

Run: `./mvnw test -pl inference-api -Dtest=InferenceOutputTest -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: FAIL — `InferenceOutput.of()` does not exist, constructor signature wrong.

- [ ] **Step 3: Implement InferenceOutput as final class**

Replace the record in `inference-api/src/main/java/io/casehub/inference/InferenceOutput.java` with the full final class implementation from spec §1. Key elements:
- Private `Map<String, float[][]> outputs` with deep defensive copy in constructor
- `of(float... values)` static factory wrapping in `Map.of("output", new float[][]{values})`
- `values()` returning clone, throwing for multi-output
- `output(String)` returning deep-cloned `float[][]`
- `vector(String)` returning `output(name)[0]`
- `outputNames()` returning `outputs.keySet()`
- `equals()` using `Arrays.deepEquals` per key
- `hashCode()` using `Arrays.deepHashCode` per key

- [ ] **Step 4: Run InferenceOutput tests — expect pass**

Run: `./mvnw test -pl inference-api -Dtest=InferenceOutputTest -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS

- [ ] **Step 5: Update InMemoryInferenceModel**

Modify `inference-inmem/src/main/java/io/casehub/inference/inmem/InMemoryInferenceModel.java`:

1. Change `returning(float...)` to use `InferenceOutput.of(snapshot.clone())`
2. Change `withFunction()` to use `InferenceOutput.of(fn.apply(input))`
3. Add `returningMulti(Map<String, float[][]>)` factory:

```java
public static InMemoryInferenceModel returningMulti(Map<String, float[][]> outputs) {
    return new InMemoryInferenceModel(
        input -> null, -1, outputs);
}
```

This requires a second constructor overload that stores the multi-output map and returns `new InferenceOutput(multiOutputs)` from `run()` instead of using the function. `outputSize()` returns `OptionalInt.empty()` for multi-output models.

4. Update `run()` and `runBatch()` to use the new `InferenceOutput` constructor.

- [ ] **Step 6: Update InMemoryInferenceModel tests**

Add test for `returningMulti()` in `InMemoryInferenceModelTest`. Verify existing `returning()` and `withFunction()` tests still pass via `values()`.

- [ ] **Step 7: Verify SparseEmbedder and CrossEncoderReranker still compile**

Run: `./mvnw test -pl inference-splade,inference-tasks -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS — both use `output.values()` which works for single-output models.

- [ ] **Step 8: Commit**

```
feat(inference-api): evolve InferenceOutput to final class with multi-output support

Breaking change: InferenceOutput is now a final class (was record) with
Map<String, float[][]> constructor. Deep defensive copies on construction
and access. InferenceOutput.of() factory and values() backward compat
for single-output consumers.

Refs neural-text #30
```

---

### Task 2: MultiModalEmbedder SPI

**Repo:** neural-text

**Files:**
- Create: `inference-api/src/main/java/io/casehub/inference/MultiModalEmbedder.java`
- Create: `inference-api/src/main/java/io/casehub/inference/MultiModalEmbedding.java`
- Create: `inference-api/src/main/java/io/casehub/inference/EmbeddingMode.java`
- Create: `inference-api/src/main/java/io/casehub/inference/MatryoshkaMultiModalEmbedder.java`
- Create: `inference-api/src/test/java/io/casehub/inference/MultiModalEmbeddingTest.java`
- Create: `inference-api/src/test/java/io/casehub/inference/MatryoshkaMultiModalEmbedderTest.java`

**Interfaces:**
- Consumes: `InferenceOutput` from Task 1
- Produces:
  - `MultiModalEmbedder` interface: `embed(String)`, `embedBatch(List<String>)`, `supportedModes()`, `denseDimension()`, `colbertDimension()`
  - `MultiModalEmbedding` final class: `dense()`, `sparse()`, `colbert()` — all with defensive copies
  - `EmbeddingMode` enum: `DENSE, SPARSE, COLBERT`
  - `MatryoshkaMultiModalEmbedder` decorator: truncates dense to target dimension, passes sparse/ColBERT through

- [ ] **Step 1: Write MultiModalEmbedding tests**

```java
// inference-api/src/test/java/io/casehub/inference/MultiModalEmbeddingTest.java
package io.casehub.inference;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MultiModalEmbeddingTest {

    @Test
    void denseDefensiveCopy() {
        float[] dense = {1f, 2f, 3f};
        var emb = new MultiModalEmbedding(dense, null, null);
        dense[0] = 999f;
        assertEquals(1f, emb.dense()[0]);
        emb.dense()[0] = 999f;
        assertEquals(1f, emb.dense()[0]);
    }

    @Test
    void sparseImmutable() {
        var sparse = Map.of(1, 0.5f, 2, 0.8f);
        var emb = new MultiModalEmbedding(new float[]{1f}, sparse, null);
        assertEquals(0.5f, emb.sparse().get(1));
        assertThrows(UnsupportedOperationException.class,
            () -> emb.sparse().put(3, 1.0f));
    }

    @Test
    void colbertDefensiveCopy() {
        float[][] colbert = {{1f, 2f}, {3f, 4f}};
        var emb = new MultiModalEmbedding(new float[]{1f}, null, colbert);
        colbert[0][0] = 999f;
        assertEquals(1f, emb.colbert()[0][0]);
        emb.colbert()[0][0] = 999f;
        assertEquals(1f, emb.colbert()[0][0]);
    }

    @Test
    void nullSparseAndColbertAllowed() {
        var emb = new MultiModalEmbedding(new float[]{1f}, null, null);
        assertNull(emb.sparse());
        assertNull(emb.colbert());
    }

    @Test
    void nullDenseRejected() {
        assertThrows(NullPointerException.class,
            () -> new MultiModalEmbedding(null, null, null));
    }
}
```

- [ ] **Step 2: Write MatryoshkaMultiModalEmbedder tests**

```java
// inference-api/src/test/java/io/casehub/inference/MatryoshkaMultiModalEmbedderTest.java
package io.casehub.inference;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MatryoshkaMultiModalEmbedderTest {

    @Test
    void truncatesDenseToTargetDimension() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f, 3f, 4f}, null, null, 4);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 2);

        MultiModalEmbedding result = matryoshka.embed("test");
        assertEquals(2, result.dense().length);
        assertEquals(2, matryoshka.denseDimension());
    }

    @Test
    void reNormalizesAfterTruncation() {
        float[] dense = {3f, 4f, 0f, 0f};
        MultiModalEmbedder delegate = stubEmbedder(dense, null, null, 4);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 2);

        float[] result = matryoshka.embed("test").dense();
        double norm = Math.sqrt(result[0] * result[0] + result[1] * result[1]);
        assertEquals(1.0, norm, 1e-6);
    }

    @Test
    void sparsePassedThrough() {
        var sparse = Map.of(1, 0.5f);
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f}, sparse, null, 2);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 1);

        assertNotNull(matryoshka.embed("test").sparse());
        assertEquals(0.5f, matryoshka.embed("test").sparse().get(1));
    }

    @Test
    void colbertPassedThrough() {
        float[][] colbert = {{1f, 2f}, {3f, 4f}};
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f}, null, colbert, 2);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 1);

        assertNotNull(matryoshka.embed("test").colbert());
        assertEquals(2, matryoshka.embed("test").colbert().length);
    }

    @Test
    void colbertDimensionDelegated() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f}, null, null, 1);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 1);
        assertEquals(delegate.colbertDimension(), matryoshka.colbertDimension());
    }

    @Test
    void targetExceedingDelegateRejected() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f}, null, null, 2);
        assertThrows(IllegalArgumentException.class,
            () -> new MatryoshkaMultiModalEmbedder(delegate, 5));
    }

    @Test
    void batchTruncatesAll() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f, 3f, 4f}, null, null, 4);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 2);

        List<MultiModalEmbedding> results = matryoshka.embedBatch(List.of("a", "b"));
        assertEquals(2, results.size());
        for (var r : results) assertEquals(2, r.dense().length);
    }

    private static MultiModalEmbedder stubEmbedder(
            float[] dense, Map<Integer, Float> sparse, float[][] colbert, int dim) {
        return new MultiModalEmbedder() {
            @Override public MultiModalEmbedding embed(String text) {
                return new MultiModalEmbedding(dense.clone(), sparse, colbert);
            }
            @Override public List<MultiModalEmbedding> embedBatch(List<String> texts) {
                return texts.stream().map(t -> embed(t)).toList();
            }
            @Override public Set<EmbeddingMode> supportedModes() {
                return EnumSet.of(EmbeddingMode.DENSE);
            }
            @Override public int denseDimension() { return dim; }
            @Override public OptionalInt colbertDimension() { return OptionalInt.empty(); }
        };
    }
}
```

- [ ] **Step 3: Run tests — expect compilation failure**

Run: `./mvnw test -pl inference-api -Dtest="MultiModalEmbeddingTest,MatryoshkaMultiModalEmbedderTest" -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 4: Implement EmbeddingMode, MultiModalEmbedding, MultiModalEmbedder, MatryoshkaMultiModalEmbedder**

Create all four files in `inference-api/src/main/java/io/casehub/inference/`:

1. `EmbeddingMode.java` — enum with `DENSE, SPARSE, COLBERT`
2. `MultiModalEmbedding.java` — final class per spec §2 with deep defensive copies
3. `MultiModalEmbedder.java` — interface per spec §2
4. `MatryoshkaMultiModalEmbedder.java` — decorator per spec §Matryoshka, truncates dense, re-normalizes, passes sparse/colbert through. `denseDimension()` returns target, `colbertDimension()` delegates.

- [ ] **Step 5: Run tests — expect pass**

Run: `./mvnw test -pl inference-api -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS

- [ ] **Step 6: Commit**

```
feat(inference-api): add MultiModalEmbedder SPI with MatryoshkaMultiModalEmbedder

New interface for models producing dense + sparse + ColBERT from one pass.
MatryoshkaMultiModalEmbedder decorator truncates dense to target dimension
and L2-re-normalizes, preserving existing Matryoshka truncation capability.

Refs neural-text #30
```

---

### Task 3: OnnxInferenceModel Multi-Output

**Repo:** neural-text

**Files:**
- Modify: `inference-runtime/src/main/java/io/casehub/inference/runtime/OnnxInferenceModel.java`
- Modify: `inference-runtime/src/test/java/io/casehub/inference/runtime/OnnxInferenceModelTest.java`

**Interfaces:**
- Consumes: `InferenceOutput` from Task 1
- Produces: `OnnxInferenceModel` that:
  - Extracts ALL named output tensors (not just the first)
  - Accepts rank-2 `[batch, dim]` AND rank-3 `[batch, seq_len, dim]` outputs
  - Strips ColBERT padding vectors using attention mask in `runBatch()`
  - Returns `OptionalInt.empty()` for `outputSize()` when model has multiple outputs or rank-3 outputs

**Implementation notes:**
- The current code at `OnnxInferenceModel.java:95` rejects non-rank-2 outputs: `if (shape.length != 2)`. Relax to accept rank 2 or rank 3.
- The current `run()` method extracts only `(float[][]) result.get(0).getValue()`. Change to iterate all entries.
- For `runBatch()`: the attention mask (`batchMask`) is already constructed for input padding. Use `sum(batchMask[i])` to determine actual sequence length per sample, then truncate rank-3 ColBERT output from `float[maxLen][dim]` to `float[actualLen][dim]`.
- `outputSize()`: return `OptionalInt.of(size)` only when there is exactly one output with rank 2 and a known second dimension. Otherwise `OptionalInt.empty()`.

- [ ] **Step 1: Write tests for multi-output extraction and rank-3 handling**

These tests require an ONNX model fixture. Create a minimal test model using ONNX Runtime's API (or a pre-built test fixture) that has two outputs of different shapes. If creating a fixture is impractical, test through `InMemoryInferenceModel` stubs for the contract, and add an integration test marker for when a real ONNX model is available.

For the unit-level contract: the `OnnxInferenceModel` changes are exercised by existing tests (single-output models still work via `values()`) plus new tests for multi-output. Write a test that verifies `outputSize()` returns empty for multi-output model metadata.

- [ ] **Step 2: Implement multi-output extraction in `run()`**

In `OnnxInferenceModel.run()`, iterate all session outputs:
```java
Map<String, float[][]> outputs = new LinkedHashMap<>();
for (Map.Entry<String, OnnxValue> entry : result) {
    Object value = entry.getValue().getValue();
    if (value instanceof float[][] rank2) {
        outputs.put(entry.getKey(), new float[][] { rank2[0] });
    } else if (value instanceof float[][][] rank3) {
        outputs.put(entry.getKey(), rank3[0]);
    }
}
return new InferenceOutput(outputs);
```

- [ ] **Step 3: Implement padding stripping in `runBatch()`**

For rank-3 outputs, use the attention mask to determine actual sequence length:
```java
int actualLen = 0;
for (long v : batchMask[sampleIdx]) actualLen += (int) v;
float[][] full = rank3Output[sampleIdx];
float[][] stripped = Arrays.copyOf(full, actualLen);
```

- [ ] **Step 4: Relax validation to accept rank 2 and rank 3**

Change the output validation check from `shape.length != 2` to `shape.length < 2 || shape.length > 3`.

- [ ] **Step 5: Update `outputSize()` for multi-output**

Return `OptionalInt.empty()` when there are multiple outputs or any rank-3 output.

- [ ] **Step 6: Run all inference-runtime tests**

Run: `./mvnw test -pl inference-runtime -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS

- [ ] **Step 7: Commit**

```
feat(inference-runtime): multi-output and rank-3 tensor support in OnnxInferenceModel

Extracts all named output tensors from ONNX session. Accepts rank-3
[batch, seq_len, dim] for ColBERT. Strips padding vectors using
attention mask in runBatch(). outputSize() returns empty for
multi-output or rank-3 models.

Refs neural-text #30
```

---

### Task 4: BgeM3Embedder Module

**Repo:** neural-text

**Files:**
- Create: `inference-bge-m3/pom.xml`
- Create: `inference-bge-m3/src/main/java/io/casehub/inference/bgem3/BgeM3Embedder.java`
- Create: `inference-bge-m3/src/test/java/io/casehub/inference/bgem3/BgeM3EmbedderTest.java`
- Modify: `pom.xml` (root) — add `inference-bge-m3` module

**Interfaces:**
- Consumes: `InferenceModel`, `InferenceOutput`, `MultiModalEmbedder`, `MultiModalEmbedding`, `EmbeddingMode` from Tasks 1-2
- Produces: `BgeM3Embedder implements MultiModalEmbedder`
  - `embed(String)` → runs `InferenceModel`, post-processes three output tensors
  - `embedBatch(List<String>)` → runs `InferenceModel.runBatch()`, post-processes each
  - `supportedModes()` → `Set.of(DENSE, SPARSE, COLBERT)`
  - `denseDimension()` → 1024
  - `colbertDimension()` → `OptionalInt.of(1024)`

**Post-processing (distinct from SPLADE):**
- Dense (`"dense"` output): L2-normalize
- Sparse (`"sparse"` output): ReLU → threshold at 0.01 → `Map<Integer, Float>`. NOT log-saturation — BGE-M3 uses `relu(linear(hidden_state))` per-token then max-pools to vocab indices.
- ColBERT (`"colbert"` output): L2-normalize each row

- [ ] **Step 1: Create Maven module**

Create `inference-bge-m3/pom.xml` with:
- Parent: neural-text root pom
- ArtifactId: `casehub-inference-bge-m3`
- Dependencies: `casehub-inference-api` (compile), `casehub-inference-inmem` (test), `junit-jupiter` (test)

Add `<module>inference-bge-m3</module>` to root pom.

- [ ] **Step 2: Write BgeM3Embedder tests**

```java
// inference-bge-m3/src/test/java/io/casehub/inference/bgem3/BgeM3EmbedderTest.java
package io.casehub.inference.bgem3;

import io.casehub.inference.*;
import io.casehub.inference.inmem.InMemoryInferenceModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BgeM3EmbedderTest {

    @Test
    void embedReturnsDenseSparseColbert() {
        InferenceModel model = stubBgeM3Model();
        BgeM3Embedder embedder = new BgeM3Embedder(model);

        MultiModalEmbedding result = embedder.embed("test");
        assertNotNull(result.dense());
        assertNotNull(result.sparse());
        assertNotNull(result.colbert());
    }

    @Test
    void denseIsL2Normalized() {
        InferenceModel model = stubBgeM3Model();
        BgeM3Embedder embedder = new BgeM3Embedder(model);

        float[] dense = embedder.embed("test").dense();
        double norm = 0;
        for (float f : dense) norm += f * f;
        assertEquals(1.0, Math.sqrt(norm), 1e-5);
    }

    @Test
    void sparseThresholdsAtPointZeroOne() {
        float[] sparseRaw = new float[100];
        sparseRaw[5] = 0.5f;
        sparseRaw[10] = 0.005f;  // below threshold
        sparseRaw[15] = 0.02f;

        InferenceModel model = stubWithSparse(sparseRaw);
        BgeM3Embedder embedder = new BgeM3Embedder(model);

        Map<Integer, Float> sparse = embedder.embed("test").sparse();
        assertTrue(sparse.containsKey(5));
        assertFalse(sparse.containsKey(10));
        assertTrue(sparse.containsKey(15));
    }

    @Test
    void sparseNegativeValuesReLUdToZero() {
        float[] sparseRaw = new float[100];
        sparseRaw[5] = -1.0f;
        sparseRaw[10] = 0.5f;

        InferenceModel model = stubWithSparse(sparseRaw);
        BgeM3Embedder embedder = new BgeM3Embedder(model);

        Map<Integer, Float> sparse = embedder.embed("test").sparse();
        assertFalse(sparse.containsKey(5));
        assertTrue(sparse.containsKey(10));
    }

    @Test
    void colbertRowsAreL2Normalized() {
        InferenceModel model = stubBgeM3Model();
        BgeM3Embedder embedder = new BgeM3Embedder(model);

        float[][] colbert = embedder.embed("test").colbert();
        for (float[] row : colbert) {
            double norm = 0;
            for (float f : row) norm += f * f;
            assertEquals(1.0, Math.sqrt(norm), 1e-5);
        }
    }

    @Test
    void supportedModesIncludesAll() {
        BgeM3Embedder embedder = new BgeM3Embedder(stubBgeM3Model());
        assertEquals(Set.of(EmbeddingMode.DENSE, EmbeddingMode.SPARSE,
            EmbeddingMode.COLBERT), embedder.supportedModes());
    }

    @Test
    void dimensionsCorrect() {
        BgeM3Embedder embedder = new BgeM3Embedder(stubBgeM3Model());
        assertEquals(1024, embedder.denseDimension());
        assertEquals(OptionalInt.of(1024), embedder.colbertDimension());
    }

    @Test
    void batchProducesOnePerInput() {
        BgeM3Embedder embedder = new BgeM3Embedder(stubBgeM3Model());
        List<MultiModalEmbedding> results = embedder.embedBatch(
            List.of("a", "b", "c"));
        assertEquals(3, results.size());
    }

    private static InferenceModel stubBgeM3Model() {
        return InMemoryInferenceModel.returningMulti(Map.of(
            "dense", new float[][]{{3f, 4f, 0f, 0f}},
            "sparse", new float[][]{{0f, 0f, 0f, 0f, 0f, 0.5f}},
            "colbert", new float[][]{{3f, 4f}, {1f, 0f}, {0f, 1f}}
        ));
    }

    private static InferenceModel stubWithSparse(float[] sparseRaw) {
        return InMemoryInferenceModel.returningMulti(Map.of(
            "dense", new float[][]{{1f, 0f}},
            "sparse", new float[][]{sparseRaw},
            "colbert", new float[][]{{1f, 0f}}
        ));
    }
}
```

- [ ] **Step 3: Run tests — expect compilation failure**

Run: `./mvnw test -pl inference-bge-m3 -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: FAIL — `BgeM3Embedder` doesn't exist.

- [ ] **Step 4: Implement BgeM3Embedder**

```java
package io.casehub.inference.bgem3;

import io.casehub.inference.*;
import java.util.*;

public final class BgeM3Embedder implements MultiModalEmbedder {

    private static final float SPARSE_THRESHOLD = 0.01f;

    private final InferenceModel model;

    public BgeM3Embedder(InferenceModel model) {
        this.model = Objects.requireNonNull(model);
    }

    @Override
    public MultiModalEmbedding embed(String text) {
        InferenceOutput output = model.run(InferenceInput.of(text));
        return toEmbedding(output);
    }

    @Override
    public List<MultiModalEmbedding> embedBatch(List<String> texts) {
        List<InferenceInput> inputs = texts.stream()
            .map(InferenceInput::of).toList();
        List<InferenceOutput> outputs = model.runBatch(inputs);
        return outputs.stream().map(this::toEmbedding).toList();
    }

    @Override
    public Set<EmbeddingMode> supportedModes() {
        return Set.of(EmbeddingMode.DENSE, EmbeddingMode.SPARSE, EmbeddingMode.COLBERT);
    }

    @Override
    public int denseDimension() { return 1024; }

    @Override
    public OptionalInt colbertDimension() { return OptionalInt.of(1024); }

    private MultiModalEmbedding toEmbedding(InferenceOutput output) {
        float[] dense = normalize(output.vector("dense"));
        Map<Integer, Float> sparse = extractSparse(output.vector("sparse"));
        float[][] colbert = normalizeRows(output.output("colbert"));
        return new MultiModalEmbedding(dense, sparse, colbert);
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm < 1e-10) return v;
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) result[i] = (float)(v[i] / norm);
        return result;
    }

    private Map<Integer, Float> extractSparse(float[] raw) {
        Map<Integer, Float> sparse = new HashMap<>();
        for (int i = 0; i < raw.length; i++) {
            float activated = Math.max(0f, raw[i]);
            if (activated >= SPARSE_THRESHOLD) {
                sparse.put(i, activated);
            }
        }
        return Map.copyOf(sparse);
    }

    private static float[][] normalizeRows(float[][] rows) {
        float[][] result = new float[rows.length][];
        for (int r = 0; r < rows.length; r++) {
            result[r] = normalize(rows[r]);
        }
        return result;
    }
}
```

- [ ] **Step 5: Run tests — expect pass**

Run: `./mvnw test -pl inference-bge-m3 -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS

- [ ] **Step 6: Commit**

```
feat(inference-bge-m3): BgeM3Embedder — MultiModalEmbedder for BGE-M3

Wraps InferenceModel, extracts dense/sparse/ColBERT from three named
output tensors. Dense and ColBERT L2-normalized, sparse ReLU-thresholded
(no log-saturation — differs from SPLADE post-processing).

Refs neural-text #30
```

---

### Task 5: RAG Pipeline Evolution

**Repo:** neural-text

This is the largest task — it evolves the core RAG pipeline from `EmbeddingModel + SparseEmbedder + CrossEncoderReranker` to `MultiModalEmbedder`. All changes are tightly coupled and must compile together.

**Files:**
- Modify: `rag/src/main/java/io/casehub/rag/runtime/RagConfig.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/QdrantPointBuilder.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/QdrantEmbeddingIngestor.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/ReactiveQdrantEmbeddingIngestor.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/HybridCaseRetriever.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/ReactiveHybridCaseRetriever.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/RagBeanProducer.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/ReactiveRagBeanProducer.java`
- Modify: `rag/src/main/java/io/casehub/rag/runtime/BlockingToReactiveCaseRetriever.java`
- Delete: `rag/src/main/java/io/casehub/rag/runtime/MatryoshkaEmbeddingModel.java`
- Modify: all corresponding test files
- Delete: `rag/src/test/java/io/casehub/rag/runtime/MatryoshkaEmbeddingModelTest.java`

**Interfaces:**
- Consumes: `MultiModalEmbedder`, `MultiModalEmbedding`, `EmbeddingMode`, `MatryoshkaMultiModalEmbedder` from Tasks 1-2
- Produces:
  - `HybridCaseRetriever(QdrantClient, MultiModalEmbedder, TenantGuard, RagConfig)` — ColBERT MAX_SIM reranking via two-stage Qdrant query
  - `QdrantEmbeddingIngestor(QdrantClient, MultiModalEmbedder, TenantGuard, RagConfig)` — ColBERT multi-vector in collection schema and point building
  - `RagBeanProducer` injecting `MultiModalEmbedder` instead of `EmbeddingModel + SparseEmbedder + CrossEncoderReranker`
  - Reactive variants with identical constructor signatures

- [ ] **Step 1: Add `colbertVectorName()` to RagConfig**

```java
// In RagConfig.java, add after bm25VectorName():
@WithDefault("colbert")
String colbertVectorName();
```

- [ ] **Step 2: Evolve QdrantPointBuilder**

Change `buildPoint()` to accept `MultiModalEmbedding` instead of separate `Embedding denseEmbedding` + `Map<Integer, Float> sparseMap`. Add ColBERT multi-vector:

```java
static PointStruct buildPoint(
        ChunkInput chunk, CorpusRef corpus,
        MultiModalEmbedding embedding,
        int chunkIndex, RagConfig config) {
    // ... existing UUID generation ...

    Map<String, Vector> namedVectors = new HashMap<>();
    namedVectors.put(config.denseVectorName(),
        VectorFactory.vector(floatListFrom(embedding.dense())));

    if (embedding.sparse() != null) {
        // ... existing sparse vector construction from Map<Integer, Float> ...
        namedVectors.put(config.sparseVectorName(), sparseVector);
    }

    if (config.bm25Enabled()) {
        // ... existing BM25 Document vector, unchanged ...
    }

    if (embedding.colbert() != null) {
        // Multi-vector: list of dense vectors, one per token
        List<List<Float>> multiVecs = new ArrayList<>();
        for (float[] row : embedding.colbert()) {
            multiVecs.add(floatListFrom(row));
        }
        namedVectors.put(config.colbertVectorName(),
            VectorFactory.multiVector(multiVecs));
    }

    // ... existing payload construction ...
}
```

`VectorFactory.multiVector(float[][])` confirmed in Qdrant client 1.18.1 — constructs `MultiDenseVector` from nested arrays.

- [ ] **Step 3: Evolve QdrantEmbeddingIngestor**

Change constructor:
```java
QdrantEmbeddingIngestor(QdrantClient client, MultiModalEmbedder embedder,
                        TenantGuard tenantGuard, RagConfig config)
```

Change `ingest()`:
- Replace separate `embeddingModel.embedAll()` + `sparseEmbedder.embedBatch()` with single `embedder.embedBatch(texts)`
- Pass `MultiModalEmbedding` to `QdrantPointBuilder.buildPoint()`

Change `ensureCollection()`:
- Replace `embeddingModel.dimension()` with `embedder.denseDimension()`
- Add ColBERT multi-vector config when `embedder.supportedModes().contains(COLBERT)`:

```java
if (embedder.supportedModes().contains(EmbeddingMode.COLBERT)) {
    VectorParams colbertParams = VectorParams.newBuilder()
        .setSize(embedder.colbertDimension().orElseThrow())
        .setDistance(Distance.Cosine)
        .setMultivectorConfig(MultiVectorConfig.newBuilder()
            .setComparator(MultiVectorComparator.MaxSim)
            .build())
        .build();
    paramsMapBuilder.putMap(config.colbertVectorName(), colbertParams);
}
```

- Check existing collection for ColBERT vector config presence.

- [ ] **Step 4: Evolve ReactiveQdrantEmbeddingIngestor symmetrically**

Same constructor and logic changes as the blocking variant, adapted for `Uni<>` chains.

- [ ] **Step 5: Evolve HybridCaseRetriever**

Change constructor to `(QdrantClient, MultiModalEmbedder, TenantGuard, RagConfig)`.

In `retrieve()`:
1. Single embed call: `MultiModalEmbedding embedding = embedder.embed(query.searchText())`
2. Build dense prefetch from `embedding.dense()`
3. Build sparse prefetch from `embedding.sparse()` (if not null)
4. BM25 prefetch unchanged
5. RRF fusion unchanged

Replace cross-encoder reranking with ColBERT MAX_SIM two-stage query:

```java
if (embedder.supportedModes().contains(EmbeddingMode.COLBERT)
        && config.retrieval().rerankEnabled()) {
    // Wrap RRF query as inner prefetch
    QueryPoints.Builder outer = QueryPoints.newBuilder()
        .setCollectionName(collection)
        .addPrefetch(PrefetchQuery.newBuilder()
            .setQuery(rrfQuery)
            .setLimit(config.retrieval().rerankTopN())
            .addAllPrefetch(prefetchLegs))
        .setQuery(QueryFactory.nearest(
            VectorFactory.multiVector(colbertQueryVectors)))
        .setUsing(config.colbertVectorName())
        .setLimit(maxResults)
        .setWithPayload(WithPayloadSelectorFactory.enable(true));
    queryPoints = outer.build();
}
```

Remove all `CrossEncoderReranker` references and `maybeRerank()` method.

- [ ] **Step 6: Evolve ReactiveHybridCaseRetriever symmetrically**

Same changes adapted for `Uni<>` chains. Remove `QueryEmbeddings` record — replaced by `MultiModalEmbedding`.

- [ ] **Step 7: Evolve RagBeanProducer**

```java
@ApplicationScoped
public class RagBeanProducer {

    @Inject RagConfig config;
    @Inject QdrantClient client;
    @Inject MultiModalEmbedder embedder;
    @Inject Instance<CurrentPrincipal> currentPrincipalInstance;

    private MultiModalEmbedder effectiveEmbedder() {
        return config.matryoshka().dimension().isPresent()
            ? new MatryoshkaMultiModalEmbedder(embedder,
                config.matryoshka().dimension().getAsInt())
            : embedder;
    }

    private TenantGuard resolveTenantGuard() { /* unchanged */ }

    @Produces @ApplicationScoped
    QdrantEmbeddingIngestor corpusStore() {
        return new QdrantEmbeddingIngestor(client, effectiveEmbedder(),
            resolveTenantGuard(), config);
    }

    @Produces @ApplicationScoped
    HybridCaseRetriever caseRetriever() {
        return new HybridCaseRetriever(client, effectiveEmbedder(),
            resolveTenantGuard(), config);
    }
}
```

Remove all `EmbeddingModel`, `SparseEmbedder`, `CrossEncoderReranker` imports and injections.

- [ ] **Step 8: Evolve ReactiveRagBeanProducer symmetrically**

Same changes. Remove `effectiveEmbeddingModel()`, add `effectiveEmbedder()`.

- [ ] **Step 9: Evolve BlockingToReactiveCaseRetriever**

Change constructor to accept `MultiModalEmbedder` instead of separate components.

- [ ] **Step 10: Delete MatryoshkaEmbeddingModel**

Delete `rag/src/main/java/io/casehub/rag/runtime/MatryoshkaEmbeddingModel.java` and its test. The capability is now provided by `MatryoshkaMultiModalEmbedder` in `inference-api`.

- [ ] **Step 11: Update all test files**

Update test constructors and mocks. Create a test `MultiModalEmbedder` implementation for rag tests that returns deterministic embeddings.

- [ ] **Step 12: Run full rag module tests**

Run: `./mvnw test -pl rag -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS

- [ ] **Step 13: Commit**

```
feat(rag): evolve RAG pipeline from separate models to MultiModalEmbedder

HybridCaseRetriever, QdrantEmbeddingIngestor, and their reactive variants
now accept MultiModalEmbedder instead of EmbeddingModel + SparseEmbedder +
CrossEncoderReranker. ColBERT multi-vectors stored in Qdrant, reranking
via MAX_SIM two-stage query. MatryoshkaEmbeddingModel deleted — replaced
by MatryoshkaMultiModalEmbedder in inference-api.

BREAKING: constructor signatures changed on all retriever/ingestor/producer classes.

Refs neural-text #30
```

---

### Task 6: RAG Testing Module

**Repo:** neural-text

**Files:**
- Modify: `rag-testing/src/main/java/io/casehub/rag/testing/InMemoryCaseRetriever.java`
- Modify: `rag-testing/src/main/java/io/casehub/rag/testing/InMemoryEmbeddingIngestor.java`
- Modify: `rag-testing/src/main/java/io/casehub/rag/testing/InMemoryReactiveCaseRetriever.java`
- Modify: `rag-testing/src/main/java/io/casehub/rag/testing/InMemoryReactiveEmbeddingIngestor.java`
- Modify: all corresponding test files
- Modify: `rag-testing/pom.xml` — add `casehub-inference-api` dependency (for `MultiModalEmbedder`)

**Interfaces:**
- Consumes: `MultiModalEmbedder`, `MultiModalEmbedding` from Task 2
- Produces: Updated test doubles that work with `MultiModalEmbedder`-based pipeline

`InMemoryEmbeddingIngestor` and `InMemoryCaseRetriever` are `@Alternative @Priority(1)` beans used in `@QuarkusTest`. They need their CDI injection points updated from `EmbeddingModel` to `MultiModalEmbedder` where applicable, or their constructors updated to match the new RAG pipeline.

- [ ] **Step 1: Update InMemoryEmbeddingIngestor**

Remove `EmbeddingModel` and `SparseEmbedder` dependencies. The in-memory ingestor stores chunks directly — it doesn't embed. If it needs `MultiModalEmbedder` for any reason (e.g., to produce test fixtures), inject it; otherwise simplify to chunk storage only.

- [ ] **Step 2: Update InMemoryCaseRetriever**

Remove `EmbeddingModel` dependency. The in-memory retriever matches by text content, not vectors. It shouldn't need `MultiModalEmbedder` at all.

- [ ] **Step 3: Update reactive variants symmetrically**

- [ ] **Step 4: Update rag-testing tests**

- [ ] **Step 5: Run full rag-testing module tests**

Run: `./mvnw test -pl rag-testing -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS

- [ ] **Step 6: Run full neural-text build**

Run: `./mvnw verify -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: PASS — all modules compile and tests pass.

- [ ] **Step 7: Commit**

```
feat(rag-testing): adapt InMemory test doubles for MultiModalEmbedder

Update InMemoryCaseRetriever, InMemoryEmbeddingIngestor, and reactive
variants to work with the new MultiModalEmbedder-based pipeline.

Refs neural-text #30
```

---

### Task 7: Engine Adoption

**Repo:** engine

**Files:**
- Modify: `src/main/java/io/hortora/garden/inference/HybridSearchProducer.java`
- Modify: `src/main/java/io/hortora/garden/inference/CollectionMigration.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Modify: `pom.xml`
- Modify: `src/test/java/io/hortora/garden/inference/TestInferenceModelProducer.java`
- Delete: `src/test/java/io/hortora/garden/test/TestEmbeddingModel.java`
- Modify: `src/test/java/io/hortora/garden/inference/HybridSearchProducerTest.java`
- Modify: `src/test/java/io/hortora/garden/inference/HybridSearchProducerAbsentTest.java`
- Modify: `src/test/java/io/hortora/garden/inference/CollectionMigrationTest.java`
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`
- Modify: `scripts/download-models.sh`

**Interfaces:**
- Consumes: `MultiModalEmbedder`, `BgeM3Embedder`, `InferenceModel`, evolved `InferenceOutput` from Tasks 1-4; evolved `HybridCaseRetriever`, `QdrantEmbeddingIngestor` from Task 5; evolved test doubles from Task 6
- Produces: Engine wired to BGE-M3 via `MultiModalEmbedder`

- [ ] **Step 1: Update pom.xml**

1. Replace `quarkus-langchain4j-ollama` dependency with `casehub-inference-bge-m3`
2. Ensure `casehub-inference-api` dependency is present
3. Keep `casehub-rag`, `casehub-rag-testing` (already present)

- [ ] **Step 2: Update HybridSearchProducer**

Replace existing SPLADE + reranker producers with single BGE-M3 producer:

```java
@ApplicationScoped
public class HybridSearchProducer {

    @Produces
    @Singleton
    @LookupIfProperty(name = "casehub.inference.models.bge-m3.model-path",
                       stringValue = ".+", match = StringValueMatch.REGEX)
    MultiModalEmbedder multiModalEmbedder(
            @Inference("bge-m3") InferenceModel model) {
        return new BgeM3Embedder(model);
    }
}
```

- [ ] **Step 3: Update CollectionMigration**

Replace `sparseEmbedderInstance.isResolvable()` check with `multiModalEmbedderInstance.isResolvable()`. Add dimension mismatch detection (existing dense dim vs `embedder.denseDimension()`) and ColBERT config detection.

- [ ] **Step 4: Update application.properties**

Main:
```properties
# Remove/comment SPLADE and reranker model paths
# Add BGE-M3 model path (commented in main, enabled in dev)
# %dev.casehub.inference.models.bge-m3.model-path=${user.home}/.hortora/models/bge-m3/model.onnx
# %dev.casehub.inference.models.bge-m3.tokenizer-path=${user.home}/.hortora/models/bge-m3/tokenizer.json
# %dev.casehub.inference.models.bge-m3.maxSequenceLength=8192
```

Test:
```properties
# Keep Ollama devservices disabled
# Remove SPLADE/reranker test config if present
```

- [ ] **Step 5: Update TestInferenceModelProducer**

Replace `splade` and `reranker` routes with `bge-m3` route returning a three-output `InferenceOutput`:

```java
@ApplicationScoped @Alternative @Priority(1)
public class TestInferenceModelProducer {

    @Produces @Dependent @Inference("")
    InferenceModel model(InjectionPoint ip) {
        String name = ip.getAnnotated().getAnnotation(Inference.class).value();
        return switch (name) {
            case "bge-m3" -> InMemoryInferenceModel.returningMulti(Map.of(
                "dense", new float[][]{deterministicDense(1024)},
                "sparse", new float[][]{deterministicSparse(250002)},
                "colbert", new float[][]{{0.5f, 0.5f}, {0.3f, 0.7f}}
            ));
            default -> InMemoryInferenceModel.returning(0.5f);
        };
    }

    private static float[] deterministicDense(int dim) {
        // deterministic normalized vector
    }

    private static float[] deterministicSparse(int vocabSize) {
        // mostly zeros, a few non-zero values
    }
}
```

- [ ] **Step 6: Delete TestEmbeddingModel**

Delete `src/test/java/io/hortora/garden/test/TestEmbeddingModel.java`. No more LangChain4j `EmbeddingModel` mock needed.

- [ ] **Step 7: Update HybridSearchProducerTest**

Test that `MultiModalEmbedder` is produced when `bge-m3.model-path` is configured, and non-resolvable when absent.

- [ ] **Step 8: Update CollectionMigrationTest**

Add test cases:
- Dense dimension mismatch (768→1024) triggers re-index
- Missing ColBERT vector config triggers re-index
- Already-migrated collection is no-op

- [ ] **Step 9: Update SearchResourceTest**

Adapt to use updated `InMemoryEmbeddingIngestor` from `rag-testing`.

- [ ] **Step 10: Update download-models.sh**

Replace SPLADE + reranker downloads with BGE-M3 ONNX model download (or placeholder until ONNX export script is delivered).

- [ ] **Step 11: Run engine build**

Run: `./mvnw verify -f /Users/mdproctor/claude/hortora/engine/pom.xml`
Expected: PASS

- [ ] **Step 12: Commit**

```
feat: adopt BGE-M3 via MultiModalEmbedder — drop Ollama + SPLADE + cross-encoder

HybridSearchProducer produces MultiModalEmbedder from @Inference("bge-m3").
CollectionMigration detects dimension mismatch and missing ColBERT config.
Ollama dependency removed — dense embeddings from ONNX.
TestEmbeddingModel deleted, TestInferenceModelProducer updated for BGE-M3.

Closes #32
```

---

## File Structure Summary

### Neural-text (new/modified)

| File | Action | Task |
|------|--------|------|
| `inference-api/.../InferenceOutput.java` | Rewrite (record → final class) | 1 |
| `inference-api/.../MultiModalEmbedder.java` | Create | 2 |
| `inference-api/.../MultiModalEmbedding.java` | Create | 2 |
| `inference-api/.../EmbeddingMode.java` | Create | 2 |
| `inference-api/.../MatryoshkaMultiModalEmbedder.java` | Create | 2 |
| `inference-inmem/.../InMemoryInferenceModel.java` | Modify | 1 |
| `inference-runtime/.../OnnxInferenceModel.java` | Modify | 3 |
| `inference-bge-m3/` (entire module) | Create | 4 |
| `rag/.../RagConfig.java` | Modify | 5 |
| `rag/.../QdrantPointBuilder.java` | Modify | 5 |
| `rag/.../QdrantEmbeddingIngestor.java` | Modify | 5 |
| `rag/.../ReactiveQdrantEmbeddingIngestor.java` | Modify | 5 |
| `rag/.../HybridCaseRetriever.java` | Modify | 5 |
| `rag/.../ReactiveHybridCaseRetriever.java` | Modify | 5 |
| `rag/.../RagBeanProducer.java` | Modify | 5 |
| `rag/.../ReactiveRagBeanProducer.java` | Modify | 5 |
| `rag/.../BlockingToReactiveCaseRetriever.java` | Modify | 5 |
| `rag/.../MatryoshkaEmbeddingModel.java` | Delete | 5 |
| `rag-testing/.../InMemoryCaseRetriever.java` | Modify | 6 |
| `rag-testing/.../InMemoryEmbeddingIngestor.java` | Modify | 6 |
| `rag-testing/.../InMemoryReactiveCaseRetriever.java` | Modify | 6 |
| `rag-testing/.../InMemoryReactiveEmbeddingIngestor.java` | Modify | 6 |

### Engine (modified)

| File | Action | Task |
|------|--------|------|
| `pom.xml` | Modify | 7 |
| `.../HybridSearchProducer.java` | Rewrite | 7 |
| `.../CollectionMigration.java` | Modify | 7 |
| `application.properties` (main + test) | Modify | 7 |
| `.../TestInferenceModelProducer.java` | Rewrite | 7 |
| `.../TestEmbeddingModel.java` | Delete | 7 |
| `.../HybridSearchProducerTest.java` | Modify | 7 |
| `.../HybridSearchProducerAbsentTest.java` | Modify | 7 |
| `.../CollectionMigrationTest.java` | Modify | 7 |
| `.../SearchResourceTest.java` | Modify | 7 |
| `scripts/download-models.sh` | Modify | 7 |
