package io.hortora.garden.provenance;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

@ApplicationScoped
public class ProvenanceStore {

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

    public List<ProvenanceRecord> forwardLineage(String issueRepo, int issueNumber) {
        String sql = "SELECT issue_repo, issue_number, spec_name, ge_id, recorded_at, recorded_by FROM provenance WHERE issue_repo = ? AND issue_number = ? ORDER BY recorded_at";
        List<ProvenanceRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, issueRepo);
            ps.setInt(2, issueNumber);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to query forward lineage", e);
        }
        return results;
    }

    public List<ProvenanceRecord> reverseLineage(String geId) {
        String sql = "SELECT issue_repo, issue_number, spec_name, ge_id, recorded_at, recorded_by FROM provenance WHERE ge_id = ? ORDER BY recorded_at";
        List<ProvenanceRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, geId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to query reverse lineage", e);
        }
        return results;
    }

    public ProvenanceStats stats() {
        try (Connection conn = dataSource.getConnection()) {
            int totalRecords = queryInt(conn, "SELECT COUNT(*) FROM provenance");
            int uniqueEntries = queryInt(conn, "SELECT COUNT(DISTINCT ge_id) FROM provenance");
            int uniqueIssues = queryInt(conn, "SELECT COUNT(DISTINCT issue_repo || '#' || issue_number) FROM provenance");

            List<EntryRefCount> topReferenced = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ge_id, COUNT(*) as cnt FROM provenance GROUP BY ge_id ORDER BY cnt DESC LIMIT 10")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        topReferenced.add(new EntryRefCount(rs.getString("ge_id"), rs.getInt("cnt")));
                    }
                }
            }

            return new ProvenanceStats(totalRecords, uniqueEntries, uniqueIssues, topReferenced, 0);
        } catch (SQLException e) {
            Log.error("Failed to compute provenance stats", e);
            return new ProvenanceStats(0, 0, 0, List.of(), 0);
        }
    }

    public void deleteAll() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM provenance");
        } catch (SQLException e) {
            Log.error("Failed to delete all provenance records", e);
        }
    }

    private static ProvenanceRecord mapRow(ResultSet rs) throws SQLException {
        return new ProvenanceRecord(
                rs.getString("issue_repo"),
                rs.getInt("issue_number"),
                rs.getString("spec_name"),
                rs.getString("ge_id"),
                rs.getString("recorded_at"),
                rs.getString("recorded_by"));
    }

    private static int queryInt(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
