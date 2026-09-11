package io.hortora.garden.provenance;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.casehub.neocortex.rag.ProvenanceTracker;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProvenanceStore implements ProvenanceTracker {

    @Inject ProvenanceConfig config;

    private HikariDataSource dataSource;

    @PostConstruct
    void init() {
        String path = config.sqlitePath();
        boolean isMemory = ":memory:".equals(path) || path.isBlank();
        int effectivePoolSize = isMemory ? 1 : config.sqlitePoolMaxSize();

        if (!isMemory) {
            try {
                Files.createDirectories(Path.of(path).getParent());
            } catch (Exception e) {
                Log.warn("Could not create provenance DB directory", e);
            }
        }

        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        if (!isMemory) {
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        }
        sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqLiteConfig.setBusyTimeout(config.sqliteBusyTimeoutMs());

        SQLiteDataSource sqLiteDataSource = new SQLiteDataSource(sqLiteConfig);
        sqLiteDataSource.setUrl("jdbc:sqlite:" + path);

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqLiteDataSource);
        hikari.setMaximumPoolSize(effectivePoolSize);
        hikari.setMinimumIdle(1);

        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/provenance/migration")
                .load()
                .migrate();
    }

    @PreDestroy
    void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    public int record(String issueRepo, int issueNumber, String specName,
                      List<String> geIds, String recordedBy) {
        String timestamp = Instant.now().toString();
        String sql = """
                INSERT INTO provenance (issue_repo, issue_number, ge_id, spec_name, recorded_at, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(issue_repo, issue_number, ge_id) DO UPDATE SET
                    spec_name = CASE WHEN excluded.spec_name != '' THEN excluded.spec_name ELSE provenance.spec_name END,
                    recorded_at = excluded.recorded_at
                """;

        int count = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String geId : geIds) {
                    ps.setString(1, issueRepo);
                    ps.setInt(2, issueNumber);
                    ps.setString(3, geId);
                    ps.setString(4, specName != null ? specName : "");
                    ps.setString(5, timestamp);
                    ps.setString(6, recordedBy);
                    ps.addBatch();
                    count++;
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            Log.error("Failed to record provenance", e);
            throw new RuntimeException("Provenance recording failed", e);
        }
        return count;
    }

    // --- ProvenanceTracker SPI implementation ---

    @Override
    public String record(String retrievalContext, String actionId, String actionType,
                         List<String> documentIds, String recordedBy) {
        int issueNumber;
        try { issueNumber = Integer.parseInt(actionId); } catch (NumberFormatException e) { issueNumber = 0; }
        record(retrievalContext, issueNumber, "", documentIds, recordedBy);
        return UUID.randomUUID().toString();
    }

    public List<io.casehub.neocortex.rag.ProvenanceRecord> forwardLineage(String issueRepo, int issueNumber) {
        String sql = "SELECT issue_repo, issue_number, spec_name, ge_id, recorded_at, recorded_by FROM provenance WHERE issue_repo = ? AND issue_number = ? ORDER BY recorded_at";
        List<io.casehub.neocortex.rag.ProvenanceRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, issueRepo);
            ps.setInt(2, issueNumber);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapToSpiRecord(rs));
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to query forward lineage", e);
        }
        return results;
    }

    @Override
    public List<io.casehub.neocortex.rag.ProvenanceRecord> forwardLineage(String actionId, String actionType) {
        int issueNumber;
        try { issueNumber = Integer.parseInt(actionId); } catch (NumberFormatException e) { issueNumber = 0; }
        String sql = "SELECT issue_repo, issue_number, spec_name, ge_id, recorded_at, recorded_by FROM provenance WHERE issue_number = ? ORDER BY recorded_at";
        List<io.casehub.neocortex.rag.ProvenanceRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, issueNumber);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapToSpiRecord(rs));
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to query forward lineage via SPI", e);
        }
        return results;
    }

    @Override
    public List<io.casehub.neocortex.rag.ProvenanceRecord> reverseLineage(String documentId) {
        String sql = "SELECT issue_repo, issue_number, spec_name, ge_id, recorded_at, recorded_by FROM provenance WHERE ge_id = ? ORDER BY recorded_at";
        List<io.casehub.neocortex.rag.ProvenanceRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapToSpiRecord(rs));
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to query reverse lineage", e);
        }
        return results;
    }

    @Override
    public io.casehub.neocortex.rag.ProvenanceStats stats(String retrievalContext) {
        try (Connection conn = dataSource.getConnection()) {
            long totalRecords = queryInt(conn, "SELECT COUNT(*) FROM provenance");
            long uniqueDocuments = queryInt(conn, "SELECT COUNT(DISTINCT ge_id) FROM provenance");
            long uniqueActions = queryInt(conn, "SELECT COUNT(DISTINCT issue_repo || '#' || issue_number) FROM provenance");

            List<io.casehub.neocortex.rag.ProvenanceStats.DocumentRefCount> topReferenced = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ge_id, COUNT(*) as cnt FROM provenance GROUP BY ge_id ORDER BY cnt DESC LIMIT 10")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        topReferenced.add(new io.casehub.neocortex.rag.ProvenanceStats.DocumentRefCount(
                                rs.getString("ge_id"), rs.getLong("cnt")));
                    }
                }
            }

            return new io.casehub.neocortex.rag.ProvenanceStats(totalRecords, uniqueDocuments, uniqueActions, topReferenced, 0);
        } catch (SQLException e) {
            Log.error("Failed to compute provenance stats", e);
            return io.casehub.neocortex.rag.ProvenanceStats.EMPTY;
        }
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        String sql = "DELETE FROM provenance WHERE recorded_at < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            Log.error("Failed to purge provenance records", e);
            return 0;
        }
    }

    // --- Domain-specific stats (for ProvenanceResource) ---

    public io.casehub.neocortex.rag.ProvenanceStats stats() {
        return stats(null);
    }

    public void recordFeedbackContext(String geId, String issueRepo, int issueNumber, String outcome) {
        String timestamp = Instant.now().toString();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO feedback_context (ge_id, issue_repo, issue_number, outcome, recorded_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, geId);
            ps.setString(2, issueRepo);
            ps.setInt(3, issueNumber);
            ps.setString(4, outcome);
            ps.setString(5, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.error("Failed to record feedback context", e);
        }
    }

    public List<FeedbackContext> findFeedbackContext(String geId) {
        List<FeedbackContext> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ge_id, issue_repo, issue_number, outcome, recorded_at FROM feedback_context WHERE ge_id = ? ORDER BY recorded_at DESC")) {
            ps.setString(1, geId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new FeedbackContext(
                            rs.getString("ge_id"), rs.getString("issue_repo"),
                            rs.getInt("issue_number"), rs.getString("outcome"),
                            rs.getString("recorded_at")));
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to query feedback context", e);
        }
        return results;
    }

    public void recordStaleness(String geId, String stack, String reportedBy) {
        String timestamp = Instant.now().toString();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO staleness_reports (ge_id, stack, reported_at, reported_by) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, geId);
            ps.setString(2, stack);
            ps.setString(3, timestamp);
            ps.setString(4, reportedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.error("Failed to record staleness report", e);
        }
    }

    public List<StalenessReport> findStalenessReports(String geId) {
        List<StalenessReport> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ge_id, stack, reported_at, reported_by FROM staleness_reports WHERE ge_id = ? ORDER BY reported_at DESC")) {
            ps.setString(1, geId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new StalenessReport(
                            rs.getString("ge_id"), rs.getString("stack"),
                            rs.getString("reported_at"), rs.getString("reported_by")));
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to query staleness reports", e);
        }
        return results;
    }

    public List<StalenessReport> findAllStalenessReports() {
        List<StalenessReport> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT ge_id, stack, reported_at, reported_by FROM staleness_reports ORDER BY reported_at DESC")) {
            while (rs.next()) {
                results.add(new StalenessReport(
                        rs.getString("ge_id"), rs.getString("stack"),
                        rs.getString("reported_at"), rs.getString("reported_by")));
            }
        } catch (SQLException e) {
            Log.error("Failed to query all staleness reports", e);
        }
        return results;
    }

    public void deleteAll() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM provenance");
            stmt.executeUpdate("DELETE FROM staleness_reports");
            stmt.executeUpdate("DELETE FROM feedback_context");
        } catch (SQLException e) {
            Log.error("Failed to delete all provenance records", e);
        }
    }

    private static io.casehub.neocortex.rag.ProvenanceRecord mapToSpiRecord(ResultSet rs) throws SQLException {
        String recordedAt = rs.getString("recorded_at");
        Instant timestamp;
        try { timestamp = Instant.parse(recordedAt); } catch (Exception e) { timestamp = Instant.now(); }
        return new io.casehub.neocortex.rag.ProvenanceRecord(
                UUID.randomUUID().toString(),
                rs.getString("issue_repo"),
                String.valueOf(rs.getInt("issue_number")),
                "github-issue",
                rs.getString("ge_id"),
                rs.getString("recorded_by"),
                timestamp);
    }

    private static int queryInt(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
