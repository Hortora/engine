package io.hortora.garden.outcome;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrCaseSummary;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrScanRequest;
import io.casehub.neocortex.memory.cbr.CbrScanResult;
import io.casehub.neocortex.memory.cbr.ResolvedCase;
import io.casehub.platform.api.path.Path;
import io.hortora.garden.config.GardenConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GardenOutcomeService {

    static final String CASE_TYPE = "garden-outcome";

    @Inject CbrCaseMemoryStore cbrStore;
    @Inject GardenConfig config;

    public String recordOutcome(String geId, String issueRepo, int issueNumber,
                                 String workContext, double successRate, String detail) {
        String tenantId = config.id();

        boolean exists = scanAllCases(tenantId).stream()
                .anyMatch(c -> geId.equals(c.caseId()));

        if (!exists) {
            String problem = workContext + " (" + issueRepo + "#" + issueNumber + ")";
            ResolvedCase cbrCase = new ResolvedCase(problem, geId, null, null, Map.of(), List.of(), null, null);
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

        List<CbrCaseSummary> cases = scanAllCases(tenantId);

        if (cases.isEmpty()) {
            return "No outcome data recorded yet.";
        }

        List<CbrCaseSummary> sorted = cases.stream()
                .sorted(Comparator.comparingDouble(c -> c.trustScore() != null ? c.trustScore() : 1.0))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("## Garden Entry Outcome Report\n\n");
        sb.append("Entries with recorded outcomes, sorted by trust score (lowest first):\n\n");

        for (CbrCaseSummary c : sorted) {
            sb.append("- **").append(c.caseId()).append("**");
            if (c.trustScore() != null) {
                sb.append(" — trust: ").append(String.format("%.2f", c.trustScore()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public void clearAll() {
        String tenantId = config.id();
        cbrStore.eraseByScope(Path.of("garden", tenantId), tenantId);
    }

    private List<CbrCaseSummary> scanAllCases(String tenantId) {
        List<CbrCaseSummary> all = new ArrayList<>();
        String cursor = null;
        do {
            CbrScanResult page = cbrStore.scan(new CbrScanRequest(
                    tenantId, new MemoryDomain("knowledge"), CASE_TYPE, 100, cursor));
            all.addAll(page.items());
            cursor = page.hasMore() ? page.nextCursor() : null;
        } while (cursor != null);
        return all;
    }
}
