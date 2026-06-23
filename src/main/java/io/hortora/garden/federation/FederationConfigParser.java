package io.hortora.garden.federation;

import io.hortora.garden.config.GardenConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class FederationConfigParser {

    private static final Set<String> VALID_ROLES = Set.of("canonical", "child", "peer");
    private static final Set<String> VALID_SEARCH_ORDERS = Set.of("fallback", "always");
    private static final double DEFAULT_RELEVANCE_THRESHOLD = 0.7;
    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final int DEFAULT_FEDERATION_TIMEOUT_SECONDS = 5;

    @Inject
    GardenConfig gardenConfig;

    private FederationConfig config;

    void onStartup(@Observes StartupEvent event) {
        try {
            config = parse(gardenConfig.schemaPath(), gardenConfig.id(), gardenConfig.idPrefix());
            Log.infof("Federation config: role=%s, upstream=%d, peers=%d",
                    config.role(), config.upstream().size(), config.peers().size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SCHEMA.md at " + gardenConfig.schemaPath(), e);
        }
    }

    @Produces
    @Singleton
    FederationConfig produce() {
        return config;
    }

    @SuppressWarnings("unchecked")
    static FederationConfig parse(Path schemaPath, String fallbackId, String fallbackPrefix)
            throws IOException {

        if (!Files.exists(schemaPath)) {
            return defaultConfig(fallbackId, fallbackPrefix);
        }

        String content = Files.readString(schemaPath);
        content = content.replace("\r\n", "\n");
        String[] parts = content.split("---\n", 3);
        if (parts.length < 2 || parts[1].isBlank()) {
            return defaultConfig(fallbackId, fallbackPrefix);
        }

        Map<String, Object> yaml;
        try {
            yaml = new Yaml().load(parts[1]);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse SCHEMA.md YAML: " + e.getMessage(), e);
        }

        if (yaml == null) {
            return defaultConfig(fallbackId, fallbackPrefix);
        }

        String gardenId = requireString(yaml, "id", "SCHEMA.md must have an 'id' field");
        String idPrefix = requireString(yaml, "id-prefix", "SCHEMA.md must have an 'id-prefix' field");

        Map<String, Object> federation = (Map<String, Object>) yaml.get("federation");
        if (federation == null) {
            return new FederationConfig(gardenId, idPrefix, "canonical",
                    DEFAULT_RELEVANCE_THRESHOLD, DEFAULT_MAX_DEPTH, DEFAULT_FEDERATION_TIMEOUT_SECONDS, List.of(), List.of());
        }

        String role = (String) federation.getOrDefault("role", "canonical");
        if (!VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException(
                    "Invalid federation role: '" + role + "'. Must be one of: " + VALID_ROLES);
        }

        double threshold = federation.containsKey("relevance-threshold")
                ? ((Number) federation.get("relevance-threshold")).doubleValue()
                : DEFAULT_RELEVANCE_THRESHOLD;

        int maxDepth = federation.containsKey("max-depth")
                ? ((Number) federation.get("max-depth")).intValue()
                : DEFAULT_MAX_DEPTH;

        int timeoutSeconds = federation.containsKey("timeout-seconds")
                ? ((Number) federation.get("timeout-seconds")).intValue()
                : DEFAULT_FEDERATION_TIMEOUT_SECONDS;

        List<FederationConfig.UpstreamRef> upstream = parseUpstream(
                (List<Map<String, Object>>) federation.get("upstream"));
        List<FederationConfig.PeerRef> peers = parsePeers(
                (List<Map<String, Object>>) federation.get("peers"));

        return new FederationConfig(gardenId, idPrefix, role, threshold, maxDepth, timeoutSeconds, upstream, peers);
    }

    private static List<FederationConfig.UpstreamRef> parseUpstream(List<Map<String, Object>> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(entry -> {
            String url = requireString(entry, "url", "Upstream entry must have a 'url' field");
            validateUrl(url);
            String prefix = requireString(entry, "id-prefix", "Upstream entry must have an 'id-prefix' field");
            String order = (String) entry.getOrDefault("search-order", "fallback");
            if (!VALID_SEARCH_ORDERS.contains(order)) {
                throw new IllegalArgumentException(
                        "Invalid search-order: '" + order + "'. Must be one of: " + VALID_SEARCH_ORDERS);
            }
            return new FederationConfig.UpstreamRef(url, prefix, order);
        }).toList();
    }

    private static List<FederationConfig.PeerRef> parsePeers(List<Map<String, Object>> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(entry -> {
            String url = requireString(entry, "url", "Peer entry must have a 'url' field");
            validateUrl(url);
            String prefix = requireString(entry, "id-prefix", "Peer entry must have an 'id-prefix' field");
            return new FederationConfig.PeerRef(url, prefix);
        }).toList();
    }

    private static String requireString(Map<String, Object> map, String key, String message) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.toString();
    }

    private static void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null) {
                throw new IllegalArgumentException("URL has no scheme: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL in federation config: " + url, e);
        }
    }

    private static FederationConfig defaultConfig(String gardenId, String idPrefix) {
        return new FederationConfig(gardenId, idPrefix, "canonical",
                DEFAULT_RELEVANCE_THRESHOLD, DEFAULT_MAX_DEPTH, DEFAULT_FEDERATION_TIMEOUT_SECONDS, List.of(), List.of());
    }
}
