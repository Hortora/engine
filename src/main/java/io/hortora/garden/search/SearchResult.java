package io.hortora.garden.search;

import com.fasterxml.jackson.annotation.JsonInclude;

public record SearchResult(
        String id,
        String title,
        String domain,
        String type,
        int score,
        String body,
        double relevance,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double crossEncoderScore,
        String source,
        String sourcePrefix,
        @com.fasterxml.jackson.annotation.JsonIgnore java.util.List<String> seeAlsoIds,
        @com.fasterxml.jackson.annotation.JsonIgnore java.util.Map<String, String> metadata) {

    public SearchResult(String id, String title, String domain, String type,
                        int score, String body, double relevance, Double crossEncoderScore,
                        String source, String sourcePrefix) {
        this(id, title, domain, type, score, body, relevance, crossEncoderScore,
             source, sourcePrefix, java.util.List.of(), java.util.Map.of());
    }

    public SearchResult(String id, String title, String domain, String type,
                        int score, String body, double relevance, Double crossEncoderScore,
                        String source, String sourcePrefix, java.util.List<String> seeAlsoIds) {
        this(id, title, domain, type, score, body, relevance, crossEncoderScore,
             source, sourcePrefix, seeAlsoIds, java.util.Map.of());
    }

    SearchResult withAdjustedScores(double newRelevance, Double newCeScore) {
        return new SearchResult(id, title, domain, type, score, body,
                newRelevance, newCeScore, source, sourcePrefix, seeAlsoIds, metadata);
    }

    @com.fasterxml.jackson.annotation.JsonProperty
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String stalenessThreshold() {return metadata != null ? metadata.get("staleness_threshold") : null;}

    @com.fasterxml.jackson.annotation.JsonProperty
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public java.util.List<String> tags() {
        if (metadata == null) {return java.util.List.of();}
        String joined = metadata.get("tags_joined");
        if (joined == null || joined.isEmpty()) {return java.util.List.of();}
        return java.util.List.of(joined.split("\\|"));
    }

    @com.fasterxml.jackson.annotation.JsonProperty
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String lastReviewed() {return metadata != null ? metadata.get("last_reviewed") : null;}

    @com.fasterxml.jackson.annotation.JsonProperty
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String author() {return metadata != null ? metadata.get("author") : null;}

    @com.fasterxml.jackson.annotation.JsonProperty
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String verifiedOn() {return metadata != null ? metadata.get("verified_on") : null;}

}
