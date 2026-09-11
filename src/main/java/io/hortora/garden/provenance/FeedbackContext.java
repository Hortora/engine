package io.hortora.garden.provenance;

public record FeedbackContext(String geId, String issueRepo, int issueNumber,
                              String outcome, String recordedAt) {}
