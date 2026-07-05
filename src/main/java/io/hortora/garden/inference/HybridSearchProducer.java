package io.hortora.garden.inference;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.MatryoshkaMultiModalEmbedder;
import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.inference.bgem3.BgeM3Embedder;
import io.casehub.neocortex.inference.quarkus.Inference;
import io.casehub.neocortex.inference.quarkus.InferenceModelConfig;
import io.casehub.neocortex.rag.runtime.RagConfig;
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
    MultiModalEmbedder multiModalEmbedder(@Inference("bge-m3") InferenceModel model,
                                          RagConfig ragConfig,
                                          InferenceModelConfig inferenceConfig) {
        int maxSeqLen = inferenceConfig.models().get("bge-m3").maxSequenceLength();
        MultiModalEmbedder embedder = new BgeM3Embedder(model, maxSeqLen);
        if (ragConfig.matryoshka().dimension().isPresent()) {
            embedder = new MatryoshkaMultiModalEmbedder(embedder,
                    ragConfig.matryoshka().dimension().getAsInt());
        }
        return embedder;
    }
}
