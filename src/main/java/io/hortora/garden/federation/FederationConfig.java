package io.hortora.garden.federation;

import java.util.List;

public record FederationConfig(
        String gardenId,
        String idPrefix,
        String role,
        double relevanceThreshold,
        int maxDepth,
        List<UpstreamRef> upstream,
        List<PeerRef> peers
) {
    public record UpstreamRef(String url, String idPrefix, String searchOrder) {}
    public record PeerRef(String url, String idPrefix) {}

    public boolean hasUpstream() {
        return upstream != null && !upstream.isEmpty();
    }

    public boolean hasPeers() {
        return peers != null && !peers.isEmpty();
    }

    public boolean hasFederation() {
        return hasUpstream() || hasPeers();
    }
}
