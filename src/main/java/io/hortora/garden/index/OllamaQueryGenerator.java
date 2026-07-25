package io.hortora.garden.index;

import io.hortora.garden.config.InvertedHydeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class OllamaQueryGenerator implements QueryGenerator {

    private static final Logger LOG = Logger.getLogger(OllamaQueryGenerator.class.getName());

    static final String PROMPT_TEMPLATE =
        "Given this knowledge garden entry about JVM development, generate exactly %d short questions "
            + "that a developer would type into a search box to find this entry. "
            + "Use the same technical vocabulary the entry uses — class names, annotations, error messages. "
            + "One question per line, no numbering, no explanations.";

    private static final int MIN_QUERY_LENGTH = 15;
    private static final int MAX_QUERY_LENGTH = 150;
    private static final int MIN_VALID_QUERIES = 2;
    private static final int MAX_INPUT_CHARS = 2000;

    private final InvertedHydeConfig config;
    private final HttpClient httpClient;
    private final String versionHash;

    @Inject
    public OllamaQueryGenerator(InvertedHydeConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.versionHash = computeVersionHash(
            PROMPT_TEMPLATE, config.ollama().model(), config.queryCount());
    }

    @Override
    public Optional<List<String>> generate(String title, String body, Path entryPath) {
        Path queriesPath = sidecarPath(entryPath);

        List<String> cached = readCache(queriesPath, entryPath, versionHash);
        if (!cached.isEmpty()) {
            return Optional.of(cached);
        }

        String input = buildInput(title, body);
        String prompt = PROMPT_TEMPLATE.formatted(config.queryCount()) + "\n\n" + input;

        try {
            String raw = callOllama(prompt);
            List<String> queries = validateOutput(raw, config.queryCount());
            if (queries.isEmpty()) {
                LOG.warning(() -> "Query generation produced insufficient valid output for " + entryPath.getFileName());
                return Optional.empty();
            }
            writeCache(queriesPath, versionHash, config.ollama().model(), config.queryCount(), queries);
            return Optional.of(queries);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Ollama query generation failed for " + entryPath.getFileName(), e);
            return Optional.empty();
        }
    }

    private String callOllama(String prompt) throws IOException, InterruptedException {
        String jsonBody = "{\"model\": \"%s\", \"prompt\": %s, \"stream\": false}".formatted(
            config.ollama().model(), escapeJson(prompt));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.ollama().host() + "/api/generate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(60))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Ollama returned status " + response.statusCode());
        }

        return extractResponse(response.body());
    }

    static List<String> validateOutput(String raw, int expectedCount) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<String> valid = new ArrayList<>();
        String[] lines = raw.strip().split("\n");

        for (int i = 0; i < Math.min(lines.length, expectedCount); i++) {
            String line = lines[i].strip();
            line = stripNumbering(line);
            if (line.length() >= MIN_QUERY_LENGTH && line.length() <= MAX_QUERY_LENGTH) {
                valid.add(line);
            }
        }

        return valid.size() >= MIN_VALID_QUERIES ? valid : List.of();
    }

    private static String stripNumbering(String line) {
        return line.replaceFirst("^\\d+[.)\\-]\\s*", "");
    }

    static String computeVersionHash(String promptTemplate, String model, int queryCount) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(promptTemplate.getBytes());
            md.update(model.getBytes());
            md.update(String.valueOf(queryCount).getBytes());
            return HexFormat.of().formatHex(md.digest()).substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    static List<String> readCache(Path queriesPath, Path entryPath, String expectedVersion) {
        if (!Files.exists(queriesPath)) {
            return List.of();
        }
        try {
            if (Files.exists(entryPath)
                && Files.getLastModifiedTime(entryPath).compareTo(Files.getLastModifiedTime(queriesPath)) > 0) {
                return List.of();
            }

            List<String> lines = Files.readAllLines(queriesPath);
            if (lines.isEmpty() || !lines.getFirst().startsWith("# v:" + expectedVersion)) {
                return List.of();
            }

            return lines.stream()
                .skip(1)
                .filter(l -> !l.isBlank())
                .toList();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to read cache " + queriesPath, e);
            return List.of();
        }
    }

    static void writeCache(Path queriesPath, String versionHash, String model, int queryCount,
                           List<String> queries) {
        try {
            String header = "# v:" + versionHash + " model=" + model + " count=" + queryCount;
            List<String> lines = new ArrayList<>();
            lines.add(header);
            lines.addAll(queries);
            Files.writeString(queriesPath, String.join("\n", lines) + "\n");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write query cache " + queriesPath, e);
        }
    }

    static Path sidecarPath(Path entryPath) {
        String filename = entryPath.getFileName().toString();
        String queriesFilename = filename.replaceFirst("\\.md$", ".queries");
        return entryPath.resolveSibling(queriesFilename);
    }

    private static String buildInput(String title, String body) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("Title: ").append(title).append("\n\n");
        }
        if (body != null) {
            int limit = Math.min(body.length(), MAX_INPUT_CHARS);
            sb.append(body, 0, limit);
        }
        return sb.toString();
    }

    private static String escapeJson(String text) {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            + "\"";
    }

    private static String extractResponse(String jsonBody) {
        int idx = jsonBody.indexOf("\"response\"");
        if (idx < 0) return "";
        int colonIdx = jsonBody.indexOf(':', idx);
        if (colonIdx < 0) return "";
        int startQuote = jsonBody.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = startQuote + 1; i < jsonBody.length(); i++) {
            char c = jsonBody.charAt(i);
            if (c == '\\' && i + 1 < jsonBody.length()) {
                char next = jsonBody.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
