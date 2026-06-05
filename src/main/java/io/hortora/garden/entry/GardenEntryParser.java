package io.hortora.garden.entry;

import jakarta.enterprise.context.ApplicationScoped;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GardenEntryParser {

    public GardenEntry parse(Path file) throws IOException {
        String content = Files.readString(file);
        String[] parts = content.split("---\n", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("No YAML frontmatter in: " + file);
        }

        Map<String, Object> fm = new Yaml().load(parts[1]);
        String body = parts[2].strip();

        return new GardenEntry(
                file.toString(),
                (String) fm.get("title"),
                (String) fm.get("domain"),
                (String) fm.get("type"),
                fm.get("score") instanceof Number n ? n.intValue() : 0,
                fm.get("tags") instanceof List<?> tags ? tags.stream().map(Object::toString).toList() : List.of(),
                fm.get("submitted") instanceof String s ? s : String.valueOf(fm.get("submitted")),
                body);
    }
}
