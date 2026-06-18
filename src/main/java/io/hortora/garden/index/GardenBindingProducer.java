package io.hortora.garden.index;

import io.casehub.corpus.zip.FlatChangeSource;
import io.casehub.corpus.zip.FlatCorpusStore;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.runtime.CorpusIngestionBinding;
import io.hortora.garden.config.GardenConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@ApplicationScoped
public class GardenBindingProducer {

    @Inject GardenConfig gardenConfig;
    @Inject GardenMetadataExtractor extractor;

    @Produces
    @Singleton
    CorpusIngestionBinding gardenBinding() {
        FlatCorpusStore store = new FlatCorpusStore(gardenConfig.path());
        FlatChangeSource changeSource = new FlatChangeSource(store, gardenConfig.path());
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        return new CorpusIngestionBinding(
                gardenConfig.id(), corpusRef, changeSource, store, extractor);
    }
}
