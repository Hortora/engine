package io.hortora.garden.config;

import io.casehub.neocortex.corpus.management.impl.FederationConfig;
import io.casehub.neocortex.corpus.management.impl.GardenMetadataExtractor;
import io.casehub.neocortex.corpus.management.impl.GardenReindexService;
import io.casehub.neocortex.corpus.management.impl.ProvenanceStore;
import io.casehub.neocortex.corpus.management.impl.SearchProfileStore;
import io.casehub.neocortex.corpus.management.impl.SearchResource;
import io.casehub.neocortex.corpus.management.impl.SearchScoringConfig;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import io.hortora.garden.federation.ChainWalker;
import io.hortora.garden.inference.CollectionMigration;
import io.hortora.garden.search.SearchConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@ApplicationScoped
public class CorpusManagementProducer {

    @Inject GardenConfig gardenConfig;
    @Inject CaseRetriever caseRetriever;
    @Inject FederationConfig federationConfig;
    @Inject ChainWalker chainWalker;
    @Inject SearchConfig searchConfig;
    @Inject io.hortora.garden.provenance.ProvenanceConfig provenanceConfig;
    @Inject Instance<CollectionMigration> collectionMigration;
    @Inject EmbeddingIngestor embeddingIngestor;

    @Produces @Singleton
    SearchResource searchResource() {
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        SearchResource.SearchConfig config = new SearchResource.SearchConfig(
            searchConfig.scoreFloor(), searchConfig.gapThreshold(),
            searchConfig.minResults(), searchConfig.scoreBoostWeight()
        );
        SearchScoringConfig scoring = new SearchScoringConfig() {
            @Override public boolean temporalDecayEnabled() { return true; }
            @Override public boolean versionScoringEnabled() { return true; }
            @Override public double versionDecayFactor() { return 0.03; }
            @Override public double versionDecayFloor() { return 0.5; }
            @Override public double versionTopicWeightDefault() { return 0.3; }
        };
        return new SearchResource(caseRetriever, corpusRef, federationConfig,
            chainWalker::walk, config, scoring, searchProfileStore());
    }

    @Produces @Singleton
    ProvenanceStore provenanceStore() {
        return new ProvenanceStore(
            SqliteDataSourceFactory.create(provenanceConfig.sqlitePath(),
                provenanceConfig.sqlitePoolMaxSize(), provenanceConfig.sqliteBusyTimeoutMs())
        );
    }

    @Produces @Singleton
    GardenReindexService reindexService() {
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        return new GardenReindexService(embeddingIngestor, corpusRef,
            collectionMigration.get()::resetCorpus);
    }

    @Produces @Singleton
    GardenMetadataExtractor metadataExtractor() {
        return new GardenMetadataExtractor();
    }

    @Produces @Singleton
    SearchProfileStore searchProfileStore() {
        String dbPath = System.getProperty("user.home") + "/.hortora/stats/search-profiles.db";
        return new SearchProfileStore(
            SqliteDataSourceFactory.create(dbPath, 3, 5000)
        );
    }
}
