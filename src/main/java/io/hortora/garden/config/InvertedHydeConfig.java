package io.hortora.garden.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.inverted-hyde")
public interface InvertedHydeConfig {

    @WithDefault("false")
    boolean enabled();

    Ollama ollama();

    @WithDefault("3")
    int queryCount();

    interface Ollama {
        @WithDefault("http://localhost:11434")
        String host();

        @WithDefault("gemma3:4b")
        String model();
    }
}
