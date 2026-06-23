package io.hortora.garden.federation;

import java.util.List;

public record FederationConfig(
        String gardenId,
        String idPrefix,
        String role,
        double relevanceThreshold,
        int maxDepth,
        int federationTimeoutSeconds,
        List<UpstreamRef> upstream,
        List<PeerRef> peers
) {
    public FederationConfig {
        upstream = upstream == null ? List.of() : upstream;
        peers = peers == null ? List.of() : peers;
    }

    public record UpstreamRef(String url, String idPrefix, String searchOrder) {}
    public record PeerRef(String url, String idPrefix) {}

    public boolean hasUpstream() {
        return !upstream.isEmpty();
    }

    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    public boolean hasFederation() {
        return hasUpstream() || hasPeers();
    }
}
