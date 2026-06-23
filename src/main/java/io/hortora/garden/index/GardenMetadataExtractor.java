package io.hortora.garden.index;

import io.casehub.rag.ExtractionResult;
import io.casehub.rag.MetadataExtractor;
import jakarta.enterprise.context.ApplicationScoped;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (fm.get("tags") instanceof List<?> tags) {
            metadata.put("tags", String.join(", ", tags.stream().map(Object::toString).toList()));
        }
        if (fm.get("submitted") != null) {
            metadata.put("submitted", String.valueOf(fm.get("submitted")));
        }

        return new ExtractionResult(combinedContent, metadata);
    }
}
