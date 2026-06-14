package io.hortora.garden.federation;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WireMockFederationResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMock;
    private Path tempYaml;

    @Override
    public Map<String, String> start() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();

        tempYaml = writeFederationYaml(wireMock.port());

        return Map.of(
                "hortora.garden.schema-path", tempYaml.toString(),
                "hortora.garden.id", "test-garden",
                "hortora.garden.id-prefix", "TG"
        );
    }

    @Override
    public void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
        if (tempYaml != null) {
            try {
                Files.deleteIfExists(tempYaml);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(wireMock,
                new TestInjector.MatchesType(WireMockServer.class));
    }

    private static Path writeFederationYaml(int port) {
        String yaml = """
                ---
                schema-version: 2.0.0
                id: test-garden
                id-prefix: TG

                federation:
                  role: child
                  relevance-threshold: 0.1
                  max-depth: 5
                  upstream:
                    - url: http://localhost:%d
                      id-prefix: UG
                      search-order: fallback
                ---
                """.formatted(port);
        try {
            Path tmp = Files.createTempFile("federation-test-", ".yaml");
            Files.writeString(tmp, yaml);
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
