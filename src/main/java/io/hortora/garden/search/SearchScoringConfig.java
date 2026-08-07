package io.hortora.garden.search;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.search.scoring")
public interface SearchScoringConfig {

    @WithDefault("true")
    boolean temporalDecayEnabled();

    @WithDefault("true")
    boolean versionScoringEnabled();

    @WithDefault("0.03")
    double versionDecayFactor();

    @WithDefault("0.5")
    double versionDecayFloor();

    @WithDefault("0.3")
    double versionTopicWeightDefault();
}
