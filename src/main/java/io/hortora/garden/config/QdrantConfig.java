package io.hortora.garden.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.qdrant")
public interface QdrantConfig {

    @WithDefault("localhost")
    String host();

    @WithDefault("6334")
    int port();

    @WithDefault("garden")
    String collection();
}
