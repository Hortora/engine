package io.hortora.garden.search;

import io.hortora.garden.config.GardenConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/entries")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class EntryResource {

    @Inject
    GardenConfig gardenConfig;

    @GET
    @Path("/{id}")
    public EntryDetail getEntry(@PathParam("id") String geId) {
        java.nio.file.Path gardenRoot   = gardenConfig.path();
        String             normalizedId = normalizeGeId(geId);
        if (normalizedId.contains("..") || normalizedId.indexOf('/') >= 0 || normalizedId.indexOf(0x5C) >= 0) {
            throw new WebApplicationException(400);
        }
        java.nio.file.Path entryFile    = findEntryFile(gardenRoot, normalizedId);
        if (entryFile == null) {
            throw new WebApplicationException(404);
        }

        try {
            String content = java.nio.file.Files.readString(entryFile);
            return parseEntry(normalizedId, content);
        } catch (java.io.IOException e) {
            Log.warn("Failed to read garden entry: " + entryFile, e);
            throw new WebApplicationException(404);
        }
    }

    static String normalizeGeId(String raw) {
        String withoutExt = raw.replaceFirst("\\.md$", "");
        if (withoutExt.contains("/")) {
            return withoutExt.substring(withoutExt.lastIndexOf('/') + 1);
        }
        return withoutExt;
    }


    static java.nio.file.Path findEntryFile(java.nio.file.Path gardenRoot, String geId) {
        String filename = geId + ".md";
        try (var stream = java.nio.file.Files.walk(gardenRoot, 2)) {
            return stream
                           .filter(p -> p.getFileName().toString().equals(filename))
                           .findFirst()
                           .orElse(null);
        } catch (java.io.IOException e) {
            Log.warn("Failed to scan garden directory for " + geId, e);
            return null;
        }
    }

    static EntryDetail parseEntry(String geId, String content) {
        String       title      = "";
        String       domain     = "";
        String       type       = "";
        int          score      = 0;
        List<String> seeAlsoIds = List.of();

        int frontmatterEnd = -1;
        if (content.startsWith("---\n")) {
            int end = content.indexOf("\n---\n", 4);
            if (end > 0) {
                frontmatterEnd = end + 5;
                String frontmatter = content.substring(4, end);
                for (String line : frontmatter.split("\n")) {
                    if (line.startsWith("title:")) {
                        title = unquote(line.substring(6).trim());
                    } else if (line.startsWith("domain:")) {
                        domain = unquote(line.substring(7).trim());
                    } else if (line.startsWith("type:")) {
                        type = unquote(line.substring(5).trim());
                    } else if (line.startsWith("score:")) {
                        try {
                            score = Integer.parseInt(line.substring(6).trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }

        String body = frontmatterEnd > 0 ? content.substring(frontmatterEnd).trim() : content;
        return new EntryDetail(geId, title, domain, type, score, body, "", "", seeAlsoIds);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {return s.substring(1, s.length() - 1);}
        if (s.length() >= 2 && s.startsWith("`") && s.endsWith("`")) {return s.substring(1, s.length() - 1);}
        return s;
    }
}
