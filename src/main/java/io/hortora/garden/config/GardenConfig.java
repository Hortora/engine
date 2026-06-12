package io.hortora.garden.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.nio.file.Path;

@ConfigMapping(prefix = "hortora.garden")
public interface GardenConfig {

    @WithDefault("${user.home}/.hortora/garden")
    Path path();

    @WithDefault("garden")
    String id();

    @WithDefault("GE")
    String idPrefix();

    @WithDefault("${hortora.garden.path}/SCHEMA.md")
    Path schemaPath();
}
