package io.hortora.garden.inference;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.MatryoshkaMultiModalEmbedder;
import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.inference.bgem3.BgeM3Embedder;
import io.casehub.neocortex.inference.quarkus.Inference;
import io.casehub.neocortex.inference.quarkus.InferenceModelConfig;
import io.casehub.neocortex.rag.cache.CachingMultiModalEmbedder;
import io.casehub.neocortex.rag.cache.EmbeddingCache;
import io.casehub.neocortex.rag.cache.EmbeddingCacheConfig;
import io.casehub.neocortex.rag.runtime.RagConfig;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.arc.properties.StringValueMatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class HybridSearchProducer {

    private static final Logger LOG =
            Logger.getLogger(HybridSearchProducer.class.getName());

    @Produces
    @Singleton
    @LookupIfProperty(name = "casehub.inference.models.bge-m3.model-path",
                       stringValue = ".+", match = StringValueMatch.REGEX)
    MultiModalEmbedder multiModalEmbedder(@Inference("bge-m3") InferenceModel model,
                                          RagConfig ragConfig,
                                          InferenceModelConfig inferenceConfig,
                                          EmbeddingCacheConfig cacheConfig) {
        int maxSeqLen = inferenceConfig.models().get("bge-m3").maxSequenceLength();
        MultiModalEmbedder embedder = new BgeM3Embedder(model, maxSeqLen);
        if (ragConfig.matryoshka().dimension().isPresent()) {
            embedder = new MatryoshkaMultiModalEmbedder(embedder,
                    ragConfig.matryoshka().dimension().getAsInt());
        }
        if (cacheConfig.enabled() && cacheConfig.path().isPresent()) {
            embedder = wrapWithCache(embedder, cacheConfig);
        }
        return embedder;
    }

    private MultiModalEmbedder wrapWithCache(MultiModalEmbedder embedder,
                                              EmbeddingCacheConfig config) {
        try {
            String path = config.path().orElseThrow();
            File dbFile = new File(path);
            File parent = dbFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String versionSuffix = config.versionSuffix().orElse("");
            String modelVersion = embedder.denseDimension()
                    + ":" + embedder.maxSequenceLength()
                    + ":" + versionSuffix;
            EmbeddingCache cache = new EmbeddingCache(path, modelVersion);
            cache.init();
            LOG.info(() -> "Embedding cache enabled at " + path
                    + " (model version: " + modelVersion + ")");
            return new CachingMultiModalEmbedder(embedder, cache, true);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "Embedding cache init failed — proceeding without cache", e);
            return embedder;
        }
    }
}
