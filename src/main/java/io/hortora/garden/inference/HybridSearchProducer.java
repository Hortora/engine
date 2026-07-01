package io.hortora.garden.inference;

import io.casehub.inference.InferenceModel;
import io.casehub.inference.MultiModalEmbedder;
import io.casehub.inference.bgem3.BgeM3Embedder;
import io.casehub.inference.quarkus.Inference;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.arc.properties.StringValueMatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class HybridSearchProducer {

    @Produces
    @Singleton
    @LookupIfProperty(name = "casehub.inference.models.bge-m3.model-path",
                       stringValue = ".+", match = StringValueMatch.REGEX)
    MultiModalEmbedder multiModalEmbedder(@Inference("bge-m3") InferenceModel model) {
        return new BgeM3Embedder(model);
    }
}
