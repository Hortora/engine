package io.hortora.garden.inference;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.quarkus.Inference;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.arc.properties.StringValueMatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class CrossEncoderProducer {

    @Produces
    @Singleton
    @LookupIfProperty(name = "casehub.inference.models.reranker.model-path",
                       stringValue = ".+", match = StringValueMatch.REGEX)
    CrossEncoderReranker crossEncoderReranker(@Inference("reranker") InferenceModel model) {
        return new CrossEncoderReranker(model);
    }
}
