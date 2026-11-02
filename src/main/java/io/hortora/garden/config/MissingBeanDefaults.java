package io.hortora.garden.config;

import io.casehub.neocortex.rag.runtime.DedupIngestionConfig;
import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class MissingBeanDefaults {

    @Produces
    @Singleton
    @DefaultBean
    LlmCredentialStore noOpCredentialStore() {
        return new LlmCredentialStore() {
            @Override public void store(String provider, String ref, Map<String, String> creds) {}
            @Override public Map<String, String> resolve(String provider, String ref) { return Map.of(); }
            @Override public void delete(String provider, String ref) {}
            @Override public List<String> listRefs(String provider) { return List.of(); }
        };
    }

    @Produces
    @Singleton
    @DefaultBean
    DedupIngestionConfig dedupIngestionConfig() {
        return new DedupIngestionConfig() {
            @Override public boolean enabled() { return false; }
            @Override public double threshold() { return 0.95; }
            @Override public Optional<String> logPath() { return Optional.empty(); }
        };
    }
}
