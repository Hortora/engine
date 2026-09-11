# Native Image Gate — Design Spec

**Date:** 2026-06-05
**Issue:** casehubio/neural-text#2
**Chapter:** C2 ([ARC42STORIES §9.3](../../ARC42STORIES.MD))
**Status:** Approved

---

## Goal

Prove that ONNX Runtime JNI and HuggingFace Tokenizers JNI both load and execute correctly inside a Quarkus native image binary on macOS ARM (Apple Silicon). Binary pass/fail — either both JNI layers work or they don't.

This gates `inference-quarkus` (C5) native image support and Hortora's distributable native binary goal. If the gate fails, C5 proceeds in JVM-only mode.

## Non-Goals

- Full SPI design (`InferenceModel`, `InferenceInput`, `InferenceOutput`) — that's C3.
- Dense embeddings, SPLADE, or any model type beyond NLI.
- Quarkus extension annotations (`@InferenceModel` qualifier) — that's C5.
- Performance benchmarking.
- CI native builds — the gate runs locally on macOS ARM only (see CI section).

## Pass/Fail Criteria

**Pass:** A `@QuarkusMain` command-mode app, compiled to a native binary, successfully:
1. Loads a HuggingFace tokenizer via DJL JNI — independently verified
2. Runs ONNX inference via ONNX Runtime JNI — independently verified
3. Tokenizes a text pair + runs inference end-to-end → returns `float[3]` logits
4. Process exits 0

**Fail:** Any of the above steps throws a JNI error, native library load failure, or GraalVM build error that cannot be resolved with configuration. Document:
- Which JNI layer failed (ORT, DJL, or both)
- Whether it's a GraalVM limitation, library limitation, or configuration gap
- Whether upstream changes are needed
- Workaround options if any

---

## Implementation Sequence

1. Bump ORT + DJL versions in parent POM; verify JVM build passes
2. `mvn dependency:tree -pl inference-runtime | grep -i awt` — early AWT kill signal
3. Check `langchain4j-embeddings-onnx` and quarkiverse extension for existing native-image metadata
4. Write `OnnxSessionLoader`, `TokenizerLoader`, `RawInference` in `inference-runtime`
5. Write `NativeImageGateCommand` in `inference-quarkus`; configure model download + test plugins
6. Green JVM tests (`@QuarkusMainTest`)
7. Run tracing agent; curate `reachability-metadata.json` + `NATIVE-IMAGE-NOTES.md`
8. Native build → iterate config → green `@QuarkusMainIntegrationTest`

---

## Module Structure — Build in Real Modules

No throwaway prototype module. The gate code goes into the production modules where it belongs. The JNI bridge logic in `inference-runtime` IS the code C3 will wrap with the SPI. The GraalVM config in `inference-quarkus` IS what C5 needs.

### inference-runtime

Minimal JNI bridge code — enough to validate both JNI layers:

```
inference-runtime/src/main/java/io/casehub/inference/runtime/
  OnnxSessionLoader.java     — load OrtEnvironment + OrtSession from model path
  TokenizerLoader.java       — load DJL HuggingFaceTokenizer from tokenizer.json path
  RawInference.java          — tokenize + run inference + return raw float[] output
```

No SPI interfaces, no `InferenceModel`, no `InferenceInput`/`InferenceOutput` types. C3 adds the SPI on top of this code. These classes are internal implementation details that the SPI will wrap — they stay as-is in C3.

### inference-quarkus

Gate test + native build profile. The `@QuarkusMain(name = "native-gate")` command-mode app lives in `src/main/java/` — it must be in main sources to be compiled into the native binary. The non-default name prevents conflicts when downstream Quarkus applications depend on `casehub-inference-quarkus` and have their own `@QuarkusMain` — Quarkus only runs the default-named main class unless overridden.

```
inference-quarkus/src/main/java/io/casehub/inference/quarkus/gate/
  NativeImageGateCommand.java       — @QuarkusMain(name = "native-gate") command-mode diagnostic entry point

inference-quarkus/src/main/resources/
  META-INF/native-image/io.casehub/casehub-inference-quarkus/
    reachability-metadata.json      — consolidated GraalVM 25 format
    native-image.properties         — --initialize-at-run-time entries
  NATIVE-IMAGE-NOTES.md             — companion doc explaining each config entry

inference-quarkus/src/test/java/io/casehub/inference/quarkus/gate/
  NativeImageGateTest.java          — @QuarkusMainTest (JVM mode)
  NativeImageGateIT.java            — @QuarkusMainIntegrationTest (native mode)
```

**C5 note:** When C5 restructures `inference-quarkus` into a proper Quarkus extension (deployment/runtime/integration-tests split), the gate test moves to the `integration-tests/` module and the `@QuarkusMain` moves with it. The config files stay in `runtime/`. This is the standard extension pattern — the gate infrastructure is not permanent in its current location.

### inference-api

Stays empty. The SPI interface comes in C3.

### Why not a throwaway module

- The JNI bridge code (`OnnxSessionLoader`, `TokenizerLoader`, `RawInference`) is exactly what `inference-runtime` will contain — writing it twice wastes effort.
- The GraalVM config files (`reachability-metadata.json`, `native-image.properties`) belong permanently in `inference-quarkus` — they'd need to move there anyway.
- If native fails, the JVM-mode code in `inference-runtime` is still correct and feeds C3.

---

## Build Configuration

### Parent POM additions

```xml
<properties>
  <!-- bump as first implementation step -->
  <onnxruntime.version>TBD-latest</onnxruntime.version>
  <djl.tokenizers.version>TBD-latest</djl.tokenizers.version>
  <download-maven-plugin.version>TBD-latest</download-maven-plugin.version>
</properties>

<pluginManagement>
  <plugins>
    <!-- existing plugins... -->
    <plugin>
      <groupId>com.googlecode.maven-download-plugin</groupId>
      <artifactId>download-maven-plugin</artifactId>
      <version>${download-maven-plugin.version}</version>
    </plugin>
  </plugins>
</pluginManagement>
```

### inference-runtime pom.xml (key dependencies)

```xml
<dependencies>
  <dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
  </dependency>
  <dependency>
    <groupId>ai.djl.huggingface</groupId>
    <artifactId>tokenizers</artifactId>
  </dependency>
</dependencies>
```

### inference-quarkus pom.xml

Existing dependencies (inference-api, inference-runtime, inference-tasks, inference-splade, quarkus-arc, quarkus-config-yaml, test deps) remain unchanged. The snippets below show only the gate-relevant additions.

**Model download and surefire config — default build (outside any profile):**

Model download runs during `generate-test-resources` — this phase only executes on `mvn test` or `mvn verify`, not `mvn compile` or `mvn install -DskipTests`. The ~87MB download is cached after first run. The `@Launch` annotation provides the model path to the test.

```xml
<build>
  <plugins>
    <!-- existing plugins (jandex, etc.) remain unchanged -->

    <plugin>
      <groupId>com.googlecode.maven-download-plugin</groupId>
      <artifactId>download-maven-plugin</artifactId>
      <executions>
        <execution>
          <id>download-onnx-model</id>
          <phase>generate-test-resources</phase>
          <goals><goal>wget</goal></goals>
          <configuration>
            <url>https://huggingface.co/Xenova/nli-deberta-v3-xsmall/resolve/main/onnx/model_quantized.onnx</url>
            <outputDirectory>${project.build.directory}/test-models/nli-deberta-v3-xsmall</outputDirectory>
            <outputFileName>model.onnx</outputFileName>
            <cacheDirectory>${user.home}/.cache/maven-download</cacheDirectory>
          </configuration>
        </execution>
        <execution>
          <id>download-tokenizer</id>
          <phase>generate-test-resources</phase>
          <goals><goal>wget</goal></goals>
          <configuration>
            <url>https://huggingface.co/Xenova/nli-deberta-v3-xsmall/resolve/main/tokenizer.json</url>
            <outputDirectory>${project.build.directory}/test-models/nli-deberta-v3-xsmall</outputDirectory>
            <outputFileName>tokenizer.json</outputFileName>
            <cacheDirectory>${user.home}/.cache/maven-download</cacheDirectory>
          </configuration>
        </execution>
      </executions>
    </plugin>

    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
        <systemPropertyVariables>
          <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
        </systemPropertyVariables>
      </configuration>
    </plugin>
  </plugins>
</build>
```

**Native profile — Quarkus application build + failsafe only:**

The native profile adds `quarkus-maven-plugin` (with `<extensions>true</extensions>`) and `maven-failsafe-plugin`. Without `-Dnative`, `inference-quarkus` builds as a normal JAR library. With `-Dnative`, it additionally builds a native binary and runs the integration test.

**Note:** `<extensions>true</extensions>` inside a profile is a known Maven pattern that works correctly with Maven 3.9+. Build extensions are technically loaded at POM parse time, but modern Maven versions handle profile-activated extensions. IDE builds and older Maven versions may not — if issues arise, move the plugin to the default build and gate native-specific goals via profile properties instead.

```xml
<profiles>
  <profile>
    <id>native</id>
    <activation>
      <property><name>native</name></property>
    </activation>
    <properties>
      <quarkus.native.enabled>true</quarkus.native.enabled>
    </properties>
    <build>
      <plugins>
        <plugin>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-maven-plugin</artifactId>
          <version>${quarkus.version}</version>
          <extensions>true</extensions>
          <executions>
            <execution>
              <goals>
                <goal>build</goal>
                <goal>generate-code</goal>
                <goal>generate-code-tests</goal>
                <goal>native-image-agent</goal>
              </goals>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-failsafe-plugin</artifactId>
          <version>${surefire-plugin.version}</version>
          <executions>
            <execution>
              <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
              </goals>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

### Build commands

```bash
# JVM mode — iterative development (fast, seconds)
# Downloads model on first test run (~87MB, cached). Runs @QuarkusMainTest.
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean verify -pl inference-runtime,inference-quarkus -am

# Native gate — validation only (slow, minutes)
# Uses GraalVM 25 (not JDK 26) — intentional: GraalVM provides native-image
# -Dnative activates quarkus-maven-plugin + failsafe for @QuarkusMainIntegrationTest
JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn clean verify -pl inference-runtime,inference-quarkus -am -Dnative
```

`-am` (also-make-dependencies) is required because `inference-quarkus` depends on `inference-api`, `inference-tasks`, and `inference-splade` (existing pom.xml). After a clean, these aren't in the local repo — `-am` builds them first.

---

## Model Provisioning

### Source: Xenova/nli-deberta-v3-xsmall

The original `cross-encoder/nli-deberta-v3-xsmall` ships PyTorch weights only — no ONNX file. Its tokenizer is SentencePiece-based (`spm.model` + `tokenizer_config.json`), not the `tokenizer.json` format that DJL's `HuggingFaceTokenizer.newInstance(path)` expects. The Xenova conversion (Transformers.js export) provides both `onnx/model_quantized.onnx` and `tokenizer.json` in the correct formats.

### Quantized model

The Xenova repo contains `onnx/model.onnx` (FP32, ~284MB) and `onnx/model_quantized.onnx` (~87MB). **Use the quantized model.** JNI bridge validation doesn't care about numerical precision — the same JNI calls and tensor operations execute regardless. DeBERTa-v3-xsmall doesn't shrink dramatically with quantization (~87MB vs ~284MB, 3.2× reduction) but the saving is still worthwhile for a test resource.

**URLs to verify at implementation time** — confirm the HuggingFace CDN path format (`/resolve/main/onnx/model_quantized.onnx`) and that the file exists in the Xenova repo.

### Model path at runtime

Model path is passed as a command-line argument to `QuarkusApplication.run(String... args)`:

```java
@Override
public int run(String... args) throws Exception {
    String modelDir = args[0];
    // ...
}
```

The `@Launch` annotation on the test provides the path as a relative argument. Both `@QuarkusMainTest` (JVM) and `@QuarkusMainIntegrationTest` (native) execute from the project root, so `target/test-models/nli-deberta-v3-xsmall` resolves correctly in both modes.

### CI caching

`maven-download-plugin` caches to `~/.cache/maven-download/`. GitHub Actions caches this directory with a stable key. First run downloads ~87MB (quantized model) + tokenizer, subsequent runs are no-ops.

---

## Gate Test Structure

### Independent JNI validation

ONNX Runtime JNI and DJL Tokenizers JNI are independent libraries with independent failure modes. The gate test validates each layer separately before testing them together, so the failure report identifies exactly which library is the problem.

### NativeImageGateCommand.java (`@QuarkusMain(name = "native-gate")`, in `src/main/java/`)

A command-mode Quarkus app implementing `QuarkusApplication`. Located in `src/main/java/` because native image compiles main sources only — test sources are not in the binary. The non-default `name = "native-gate"` prevents conflicts with downstream consumers' own `@QuarkusMain` entry points.

The model directory is passed as a command-line argument via `QuarkusApplication.run(String... args)`. The `@Launch` annotation on the test provides the args — `@QuarkusMainTest` calls `run(args)` in-process, `@QuarkusMainIntegrationTest` passes them as command-line arguments to the native binary. Both mechanisms are guaranteed to deliver the args. This avoids system property forwarding issues (Quarkus's `NativeImageLauncher` filters arbitrary properties) and test-resource scoping issues (`src/test/resources/application.properties` is not reliably included in the native binary).

Three validation phases:

```
Phase 1 — DJL Tokenizer JNI (independent)
  Load tokenizer from tokenizer.json
  Tokenize "hello world" → verify token IDs array is non-empty
  Print: "PASS: DJL Tokenizer JNI loaded and executed"

Phase 2 — ONNX Runtime JNI (independent)
  Create OrtEnvironment
  Create OrtSession from model.onnx
  Print model input/output names
  Print: "PASS: ONNX Runtime JNI loaded and session created"

Phase 3 — End-to-end inference
  Tokenize text pair: ("The weather is sunny", "It is raining")
  Create OnnxTensor inputs from token arrays
  Run session.run()
  Extract float[3] logits
  Assert contradiction score (index 0) is highest
  Print logits and result
  Print: "PASS: End-to-end inference completed"
```

Label order per model card: **contradiction=0, entailment=1, neutral=2**.

Exit 0 if all phases pass. Exit 1 on any failure, printing which phase and which JNI layer failed.

### NativeImageGateTest.java (`@QuarkusMainTest` — JVM mode)

```java
@QuarkusMainTest
public class NativeImageGateTest {
    @Test
    @Launch(value = {"target/test-models/nli-deberta-v3-xsmall"}, exitCode = 0)
    void nativeImageGatePasses(LaunchResult result) {
        assertThat(result.getOutput())
            .contains("PASS: DJL Tokenizer JNI loaded and executed")
            .contains("PASS: ONNX Runtime JNI loaded and session created")
            .contains("PASS: End-to-end inference completed");
    }
}
```

Runs in JVM mode via surefire during every `mvn test` / `mvn verify`. The model path is passed as a command-line arg via `@Launch`. Model files are downloaded during `generate-test-resources` and cached.

### NativeImageGateIT.java (`@QuarkusMainIntegrationTest` — native mode)

```java
@QuarkusMainIntegrationTest
public class NativeImageGateIT extends NativeImageGateTest {
    // Inherits test methods from NativeImageGateTest.
    // @QuarkusMainIntegrationTest launches the native binary as a process.
    // Exit code 0 + expected stdout lines = gate passes.
}
```

Runs only with `-Dnative` via maven-failsafe-plugin. Launches the native binary, asserts exit code 0 and expected phase output.

---

## GraalVM Native Image Configuration

### Config format

GraalVM 25 supports a consolidated `reachability-metadata.json` format. **Standardise on this format** — single file, easier to maintain.

JSON does not support comments. A companion `NATIVE-IMAGE-NOTES.md` in the same resources directory documents the rationale for each entry — what it fixes, when it was added, and what error appears without it.

Located at: `inference-quarkus/src/main/resources/META-INF/native-image/io.casehub/casehub-inference-quarkus/`

### Discovery process

1. Check `langchain4j-embeddings-onnx` and the quarkiverse `quarkus-langchain4j` extension for existing native-image metadata — even partial metadata for `com.microsoft.onnxruntime` is a head start over discovering everything via the agent. LangChain4j has an open feature request (#1047) for proper GraalVM AOT support, suggesting upstream metadata isn't well-packaged, but the quarkiverse extension ships in-process ONNX embedding and may have config.
2. Get the app running in JVM mode with both JNI layers working
3. Run with tracing agent to discover remaining required config
4. Curate the agent output + any borrowed metadata into `reachability-metadata.json`; document each entry in `NATIVE-IMAGE-NOTES.md`
5. Iterate: attempt native build → read error → add config → retry

### Known --initialize-at-run-time suspects

These classes load native libraries in static initializers. GraalVM runs class init at build time where the libs aren't available. List upfront to save build-fail-fix cycles:

**ONNX Runtime:**
- `ai.onnxruntime.OnnxRuntime` — calls `System.loadLibrary("onnxruntime")`

**DJL:**
- `ai.djl.util.Platform` — platform detection and native lib path resolution
- `ai.djl.huggingface.tokenizers.jni.LibUtils` — loads the tokenizers native library

These are starting points from known library internals. The tracing agent will find additional classes.

### Expected challenges (ordered by likelihood)

**1. `--initialize-at-run-time` for native lib loaders (high)**
Both libraries load `.dylib` files during class initialization. Fix: defer to runtime init.

**2. Missing JNI/reflection entries (medium)**
Tracing agent only captures exercised code paths. Fix: inspect ORT and DJL source for `native` method declarations.

**3. Native lib extraction (medium)**
Both JARs bundle native libs extracted to a temp dir at runtime. In native image, JAR resource handling differs. Fix: may need custom extraction or library path configuration.

**4. Oracle GraalVM svm-enterprise.jar class conflict (low–medium)**
Known issue where ONNX Runtime's `ai.onnxruntime.ValueInfo` conflicts with classes in `svm-enterprise.jar` in Oracle GraalVM Enterprise builds. May or may not affect 25.0.3. If hit: try GraalVM CE as fallback.

**5. AWT transitive dependency — macOS native image blocker (medium)**
The `quarkus-langchain4j` extension has an open issue (#1490) where ONNX native image builds on macOS fail with an AWT-related `UnsatisfiedLinkError`. The Easy RAG extension transitively pulls in AWT dependencies that can't be linked in native mode on macOS. Oracle GraalVM has stricter AWT enforcement than GraalVM CE.

**Relevance:** If ONNX Runtime or DJL transitively pull in AWT classes, we'll hit the same wall. This could be a hard blocker outside our control.

**Early detection:** Inspect the ONNX Runtime and DJL dependency trees for AWT transitive deps as a first implementation step (before writing any code):
```bash
mvn dependency:tree -pl inference-runtime | grep -i awt
```

**If hit:** Fallback option is Linux container build (`-Dquarkus.native.container-build=true`), which avoids the macOS AWT linking issue. This validates JNI in native image (on Linux) even if macOS-native is blocked. macOS-native validation defers until the upstream issue clears.

### Iterative approach

Each fix is documented in `NATIVE-IMAGE-NOTES.md` alongside the `reachability-metadata.json` entry it explains. This documentation feeds directly into C5's Quarkus extension.

---

## Dependency Versions

| Dependency | Parent POM | Latest | Action |
|---|---|---|---|
| ONNX Runtime | 1.17.3 | ~1.21.x | Bump — GraalVM JNI metadata may have improved. GitHub #5172 (ONNX+GraalVM) is closed. |
| DJL Tokenizers | 0.29.0 | ~0.36.0 | Bump — 7 minor versions may include native-image fixes and updated JNI surface. |
| Quarkus | 3.32.2 | (keep) | Current. |
| GraalVM | 25.0.3 | (keep) | Installed, macOS ARM. |
| maven-download-plugin | (new) | latest | Add to parent POM `<pluginManagement>`. |

**Version bump is the first implementation step** — update parent POM, verify JVM-mode build still passes, then proceed to native. If native fails with bumped versions, the old versions are a fallback data point, but starting with the latest gives the best chance of existing metadata and fixes.

---

## CI Feasibility

**The native gate is local-only.** Native image compilation for macOS ARM requires a macOS ARM runner. GitHub Actions has M1 runners but they're more expensive and add CI complexity.

For the gate: validate locally, document the result. CI runs the JVM-mode tests (`NativeImageGateTest` via surefire) to verify the code is correct. The native gate itself runs on the developer's machine.

If the gate passes and we want CI native builds in C5, options:
- GitHub Actions M1 runner (macos-14 or later)
- Linux container build (`-Dquarkus.native.container-build=true`) for CI
- Native gate as a manual CI workflow (triggered on demand, not per-push)

This decision belongs in C5, not the gate.

---

## Development Workflow

- **JVM mode** for iterative development (fast — seconds). `mvn clean verify -am` downloads model (cached), runs `@QuarkusMainTest`. All day-to-day work across C3–C6.
- **Native image** only for gate validation (slow — minutes). `mvn clean verify -am -Dnative` additionally builds native binary and runs `@QuarkusMainIntegrationTest`.
- After the gate passes, the native config is parked in `inference-quarkus`. C5 expands it into a proper Quarkus extension with deployment/runtime module split and build-time annotation processing.

---

## Outcome Artifacts

**If PASS:**
- `inference-runtime` has working JNI bridge code — direct input to C3 (wrap with SPI)
- `inference-quarkus` has curated GraalVM config + `NATIVE-IMAGE-NOTES.md` — direct input to C5
- Gate test stays until C5 restructures into deployment/runtime/integration-tests split
- Parent POM has updated ORT + DJL versions validated for native

**If FAIL:**
- Failure report identifies which JNI layer failed (ORT, DJL, or both)
- Assessment: GraalVM limitation vs library limitation vs fixable upstream
- If AWT blocker: note container-build workaround and macOS-native deferral timeline
- Workaround options documented
- C5 proceeds in JVM-only mode; Hortora native binary goal deferred
- JVM-mode code in `inference-runtime` still feeds C3

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| GraalVM (Oracle) | 25.0.3 | `native-image` compiler, macOS ARM |
| Quarkus | 3.32.2 | Application framework, native build integration |
| ONNX Runtime | bump to latest | Model execution via JNI |
| DJL HuggingFace Tokenizers | bump to latest | Tokenization via JNI |
| maven-download-plugin | latest | Model file download (`generate-test-resources` phase) |
| Xenova/nli-deberta-v3-xsmall | HuggingFace | Validation model — quantized ONNX (~87MB) |