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

        String stalenessThreshold = fm.get("staleness_threshold") instanceof String s2 ? s2 : null;
        int staleDays = parseStaleness(stalenessThreshold);
        int tier = stalenessToTier(staleDays);
        metadata.put("staleness_days", String.valueOf(staleDays));
        metadata.put("decay_tier", String.valueOf(tier));
        if (stalenessThreshold != null) metadata.put("staleness_threshold", stalenessThreshold);

        if (fm.get("verified_on") instanceof String s3) metadata.put("verified_on", s3);
        if (fm.get("author") instanceof String s4) metadata.put("author", s4);
        if (fm.get("last_reviewed") != null) {
            metadata.put("last_reviewed", toDateString(fm.get("last_reviewed")));
        }

        Map<String, List<String>> listMetadata = new LinkedHashMap<>();
        if (fm.get("tags") instanceof List<?> rawTags) {
            List<String> tagStrings = rawTags.stream().map(Object::toString).toList();
            listMetadata.put("tags", tagStrings);
            metadata.put("tags_joined", String.join("|", tagStrings));
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

    static int parseStaleness(String threshold) {
        if (threshold == null) {return 90;}
        if ("never".equalsIgnoreCase(threshold)) {return 0;}
        if (threshold.endsWith("d")) {
            try {
                return Integer.parseInt(threshold.substring(0, threshold.length() - 1));
            } catch (NumberFormatException e) {return 90;}
        }
        return 90;
    }

    static int stalenessToTier(int days) {
        if (days == 0) {
            return 3;      // evergreen
        }
        if (days <= 30) {
            return 0;     // fast
        }
        if (days <= 90) {
            return 1;     // standard
        }
        return 2;                      // slow
    }

    private static String toDateString(Object value) {
        if (value instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        }
        return String.valueOf(value);
    }


}
