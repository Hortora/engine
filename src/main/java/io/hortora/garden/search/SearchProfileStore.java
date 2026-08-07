package io.hortora.garden.search;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class SearchProfileStore {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".hortora", "stats", "profiles.db");

    @PostConstruct
    void init() {
        try (Connection conn = connect()) {
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS search_profiles (
                        name TEXT PRIMARY KEY,
                        stack TEXT NOT NULL,
                        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )""");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init profile store", e);
        }
    }

    public void put(String name, String stack) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO search_profiles (name, stack, updated_at) VALUES (?, ?, datetime('now')) " +
                             "ON CONFLICT(name) DO UPDATE SET stack = ?, updated_at = datetime('now')")) {
            ps.setString(1, name);
            ps.setString(2, stack);
            ps.setString(3, stack);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to put profile: " + name, e);
        }
    }

    public Optional<Map<String, String>> get(String name) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT stack FROM search_profiles WHERE name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(parseStack(rs.getString("stack")));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get profile: " + name, e);
        }
    }

    public boolean delete(String name) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM search_profiles WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete profile: " + name, e);
        }
    }

    public List<String> list() {
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM search_profiles ORDER BY name")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) names.add(rs.getString("name"));
            return names;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list profiles", e);
        }
    }

    public String getRawStack(String name) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT stack FROM search_profiles WHERE name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("stack") : null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get raw profile: " + name, e);
        }
    }

    static Map<String, String> parseStack(String stack) {
        Map<String, String> bom = new LinkedHashMap<>();
        if (stack == null || stack.isBlank()) return bom;
        for (String entry : stack.split("\\|")) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) bom.put(parts[0].trim(), parts[1].trim());
        }
        return bom;
    }

    private Connection connect() throws SQLException {
        DB_PATH.getParent().toFile().mkdirs();
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}
        return DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
    }
}
