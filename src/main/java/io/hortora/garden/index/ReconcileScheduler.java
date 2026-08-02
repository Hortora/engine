package io.hortora.garden.index;

import io.casehub.neocortex.rag.runtime.CorpusIngestionService;
import io.hortora.garden.config.GardenConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReconcileScheduler {

    @Inject CorpusIngestionService ingestionService;
    @Inject GardenConfig gardenConfig;

    @Scheduled(every = "${hortora.reconcile.interval:6h}",
               concurrentExecution = ConcurrentExecution.SKIP)
    void reconcile() {
        try {
            Log.info("Starting scheduled garden reconciliation");
            ingestionService.reconcile(gardenConfig.id());
            Log.info("Scheduled garden reconciliation complete");
        } catch (Exception e) {
            Log.warn("Scheduled garden reconciliation failed", e);
        }
    }
}
