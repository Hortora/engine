package io.hortora.garden.index;

import io.casehub.neocortex.rag.ExtractionResult;
import io.casehub.neocortex.rag.MetadataExtractor;
import jakarta.enterprise.context.ApplicationScoped;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class GardenMetadataExtractor implements MetadataExtractor {

    @Override
    public ExtractionResult extract(String path, byte[] content) {
        if (!path.endsWith(".md")) {
            return new ExtractionResult("", Map.of());
        }

        String text = new String(content, StandardCharsets.UTF_8);
        text = text.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return new ExtractionResult("", Map.of());
        }

        int closingIndex = text.indexOf("\n---", 3);
        if (closingIndex < 0) {
            return new ExtractionResult("", Map.of());
        }

        String frontmatterBlock = text.substring(4, closingIndex).trim();
        String body = text.substring(closingIndex + 4).trim();

        Map<String, Object> fm;
        try {
            fm = new Yaml().load(frontmatterBlock);
        } catch (Exception e) {
            return new ExtractionResult("", Map.of());
        }
        if (fm == null) {
            return new ExtractionResult("", Map.of());
        }

        String title = fm.get("title") instanceof String s ? s : null;
        String combinedContent = (title != null ? title + "\n\n" : "") + body;

        Map<String, String> metadata = new LinkedHashMap<>();
        if (title != null) metadata.put("title", title);
        if (fm.get("domain") instanceof String s) metadata.put("domain", s);
        if (fm.get("type") instanceof String s) metadata.put("type", s);
        if (fm.get("score") instanceof Number n) metadata.put("score", String.valueOf(n.intValue()));
        if (fm.get("submitted") != null) {
            metadata.put("submitted", String.valueOf(fm.get("submitted")));
        }

        Map<String, List<String>> listMetadata = new LinkedHashMap<>();
        if (fm.get("tags") instanceof List<?> tags) {
            listMetadata.put("tags", tags.stream().map(Object::toString).toList());
        }

        List<String> seeAlsoIds = extractSeeAlso(body);
        if (!seeAlsoIds.isEmpty()) {
            listMetadata.put("see_also", seeAlsoIds);
            metadata.put("see_also_ids", String.join("|", seeAlsoIds));
        }

        return new ExtractionResult(combinedContent, metadata, listMetadata);
    }

    private static final Pattern SEE_ALSO_LINE = Pattern.compile(
            "\\*\\*See also:?\\*\\*\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GE_ID_PATTERN = Pattern.compile(
            "GE-(?:\\d{8}-[0-9a-f]{6}|\\d{4})");

    static List<String> extractSeeAlso(String body) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : body.split("\n")) {
            Matcher lineMatcher = SEE_ALSO_LINE.matcher(line);
            if (lineMatcher.find()) {
                Matcher idMatcher = GE_ID_PATTERN.matcher(lineMatcher.group(1));
                while (idMatcher.find()) {
                    seen.add(idMatcher.group());
                }
            }
        }
        return new ArrayList<>(seen);
    }
}
