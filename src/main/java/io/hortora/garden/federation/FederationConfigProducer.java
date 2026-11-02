package io.hortora.garden.federation;

import io.casehub.neocortex.corpus.management.impl.FederationConfig;
import io.casehub.neocortex.corpus.management.impl.FederationConfigParser;
import io.hortora.garden.config.GardenConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;

@ApplicationScoped
public class FederationConfigProducer {

    @Inject GardenConfig gardenConfig;

    @Produces @Singleton
    FederationConfig produce() {
        try {
            return FederationConfigParser.parse(
                    gardenConfig.schemaPath(), gardenConfig.id(), gardenConfig.idPrefix());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SCHEMA.md at " + gardenConfig.schemaPath(), e);
        }
    }
}
