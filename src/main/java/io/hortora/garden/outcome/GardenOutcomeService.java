package io.hortora.garden.outcome;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.casehub.neocortex.memory.cbr.jpa.CbrCaseEntity;
import io.casehub.platform.api.path.Path;
import io.hortora.garden.config.GardenConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class GardenOutcomeService {

    static final String CASE_TYPE = "garden-outcome";

    @Inject CbrCaseMemoryStore cbrStore;
    @Inject GardenConfig config;
    @Inject EntityManager em;

    public String recordOutcome(String geId, String issueRepo, int issueNumber,
                                 String workContext, double successRate, String detail) {
        String tenantId = config.id();

        boolean exists = !em.createQuery(
                        "SELECT e.id FROM CbrCaseEntity e WHERE e.caseId = :cid AND e.tenantId = :t",
                        String.class)
                .setParameter("cid", geId)
                .setParameter("t", tenantId)
                .getResultList()
                .isEmpty();

        if (!exists) {
            String problem = workContext + " (" + issueRepo + "#" + issueNumber + ")";
            TextualCbrCase cbrCase = new TextualCbrCase(problem, geId, null, null, null, null);
            cbrStore.store(cbrCase, CASE_TYPE, geId,
                    new MemoryDomain("knowledge"), tenantId, geId,
                    Path.of("garden", tenantId));
        }

        CbrOutcome outcome = CbrOutcome.of(successRate,
                detail != null ? detail : "", Instant.now());
        cbrStore.recordOutcome(geId, tenantId, outcome);

        return "Outcome recorded for " + geId + " (success=" + successRate + ")";
    }

    public String outcomeReport() {
        String tenantId = config.id();

        List<CbrCaseEntity> cases = em.createQuery(
                                              "SELECT e FROM CbrCaseEntity e WHERE e.caseType = :ct AND e.tenantId = :t AND e.supersededAt IS NULL AND e.lastOutcomeAt IS NOT NULL",
                                              CbrCaseEntity.class)
                                      .setParameter("ct", CASE_TYPE)
                                      .setParameter("t", tenantId)
                                      .getResultList();

        if (cases.isEmpty()) {
            return "No outcome data recorded yet.";
        }

        List<CbrCaseEntity> sorted = cases.stream()
                                          .sorted(Comparator.comparingDouble(c -> c.confidence != null ? c.confidence : 1.0))
                                          .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("## Garden Entry Outcome Report\n\n");
        sb.append("Entries with recorded outcomes, sorted by confidence (lowest first):\n\n");

        for (CbrCaseEntity c : sorted) {
            sb.append("- **").append(c.caseId).append("**");
            if (c.confidence != null) {
                sb.append(" — confidence: ").append(String.format("%.2f", c.confidence));
            }
            if (c.outcome != null) {
                sb.append(" — last: ").append(c.outcome);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    @Transactional
    public void clearAll() {
        em.createQuery("DELETE FROM CbrCaseEntity e WHERE e.caseType = :ct")
                .setParameter("ct", CASE_TYPE)
                .executeUpdate();
    }
}
