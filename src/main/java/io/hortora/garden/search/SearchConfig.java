package io.hortora.garden.search;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.search")
public interface SearchConfig {

    @WithDefault("0.0")
    double scoreFloor();

    @WithDefault("2.0")
    double gapThreshold();

    @WithDefault("3")
    int minResults();

    @WithDefault("0.0")
    double scoreBoostWeight();

}
