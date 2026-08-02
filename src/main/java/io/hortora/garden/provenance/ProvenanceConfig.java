package io.hortora.garden.provenance;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.garden.provenance")
public interface ProvenanceConfig {

    @WithDefault("${user.home}/.hortora/stats/provenance.db")
    String sqlitePath();

    @WithDefault("3")
    int sqlitePoolMaxSize();

    @WithDefault("5000")
    int sqliteBusyTimeoutMs();
}
