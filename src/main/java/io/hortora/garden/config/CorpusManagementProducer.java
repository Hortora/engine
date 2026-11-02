package io.hortora.garden.config;

import io.casehub.neocortex.corpus.management.impl.FederationConfig;
import io.casehub.neocortex.corpus.management.impl.GardenMetadataExtractor;
import io.casehub.neocortex.corpus.management.impl.GardenReindexService;
import io.casehub.neocortex.corpus.management.impl.HttpFederationWalker;
import io.casehub.neocortex.corpus.management.impl.ProvenanceStore;
import io.casehub.neocortex.corpus.management.impl.SearchProfileStore;
import io.casehub.neocortex.corpus.management.impl.SearchResource;
import io.casehub.neocortex.corpus.management.impl.SearchScoringConfig;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import io.hortora.garden.search.SearchConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@ApplicationScoped
public class CorpusManagementProducer {

    @Inject
    GardenConfig                                                gardenConfig;
    @Inject
    CaseRetriever                                               caseRetriever;
    @Inject
    FederationConfig                                            federationConfig;
    @Inject
    SearchConfig                                                searchConfig;
    @Inject
    io.hortora.garden.provenance.ProvenanceConfig               provenanceConfig;
    @Inject
    EmbeddingIngestor                                           embeddingIngestor;
    @Inject
    org.eclipse.microprofile.context.ManagedExecutor            managedExecutor;
    @Inject
    Instance<io.casehub.neocortex.inference.MultiModalEmbedder> multiModalEmbedderInstance;
    @Inject
    io.qdrant.client.QdrantClient                               qdrantClient;
    @Inject
    io.casehub.neocortex.rag.CursorStore                        cursorStore;
    @Inject
    io.casehub.neocortex.rag.runtime.RagConfig                  ragConfig;

    @Produces
    @Singleton
    SearchResource searchResource() {
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        SearchResource.SearchConfig config = new SearchResource.SearchConfig(
                searchConfig.scoreFloor(), searchConfig.gapThreshold(),
                searchConfig.minResults(), searchConfig.scoreBoostWeight()
        );
        SearchScoringConfig scoring = new SearchScoringConfig() {
            @Override
            public boolean temporalDecayEnabled()     {return true;}

            @Override
            public boolean versionScoringEnabled()    {return true;}

            @Override
            public double versionDecayFactor()        {return 0.03;}

            @Override
            public double versionDecayFloor()         {return 0.5;}

            @Override
            public double versionTopicWeightDefault() {return 0.3;}
        };
        var walker = new HttpFederationWalker(federationConfig, managedExecutor,
                                              url -> io.quarkus.rest.client.reactive.QuarkusRestClientBuilder.newBuilder()
                                                                                                             .baseUri(java.net.URI.create(url))
                                                                                                             .readTimeout(federationConfig.federationTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                                                                                                             .build(io.hortora.garden.federation.HortorRemoteClient.class));
        return new SearchResource(caseRetriever, corpusRef, federationConfig,
                                  walker::walk, config, scoring, searchProfileStore());
    }

    @Produces
    @Singleton
    ProvenanceStore provenanceStore() {
        return new ProvenanceStore(
                SqliteDataSourceFactory.create(provenanceConfig.sqlitePath(),
                                               provenanceConfig.sqlitePoolMaxSize(), provenanceConfig.sqliteBusyTimeoutMs())
        );
    }


    @Produces
    @Singleton
    io.casehub.neocortex.rag.runtime.CorpusCollectionMigration corpusCollectionMigration() {
        return new io.casehub.neocortex.rag.runtime.CorpusCollectionMigration(
                multiModalEmbedderInstance.get(), qdrantClient,
                embeddingIngestor, cursorStore,
                gardenConfig.id(), "hortora", ragConfig);
    }

    @Produces
    @Singleton
    GardenReindexService reindexService() {
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        var       migration = corpusCollectionMigration();
        return new GardenReindexService(embeddingIngestor, corpusRef,
                                        (ref, id) -> migration.resetCorpus(ref));
    }

    @Produces
    @Singleton
    GardenMetadataExtractor metadataExtractor() {
        return new GardenMetadataExtractor();
    }

    @Produces
    @Singleton
    SearchProfileStore searchProfileStore() {
        String dbPath = System.getProperty("user.home") + "/.hortora/stats/search-profiles.db";
        return new SearchProfileStore(
                SqliteDataSourceFactory.create(dbPath, 3, 5000)
        );
    }

    @Produces
    @Singleton
    io.casehub.neocortex.corpus.management.impl.CorpusOutcomeService corpusOutcomeService(
            io.casehub.neocortex.memory.cbr.CbrRecordStore cbrStore) {
        return new io.casehub.neocortex.corpus.management.impl.CorpusOutcomeService(
                cbrStore, gardenConfig.id(), "garden", "garden-outcome");
    }
}
