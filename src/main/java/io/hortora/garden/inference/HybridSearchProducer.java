package io.hortora.garden.inference;

import io.casehub.inference.InferenceModel;
import io.casehub.inference.quarkus.Inference;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.arc.properties.StringValueMatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class HybridSearchProducer {

    @Produces
    @Singleton
    @LookupIfProperty(name = "casehub.inference.models.splade.model-path",
                       stringValue = ".+", match = StringValueMatch.REGEX)
    SparseEmbedder sparseEmbedder(@Inference("splade") InferenceModel spladeModel) {
        return new SparseEmbedder(spladeModel);
    }

    @Produces
    @Singleton
    @LookupIfProperty(name = "casehub.inference.models.reranker.model-path",
                       stringValue = ".+", match = StringValueMatch.REGEX)
    CrossEncoderReranker reranker(@Inference("reranker") InferenceModel rerankerModel) {
        return new CrossEncoderReranker(rerankerModel);
    }
}
