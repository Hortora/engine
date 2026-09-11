CREATE TABLE feedback_context (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ge_id           TEXT NOT NULL,
    issue_repo      TEXT NOT NULL,
    issue_number    INTEGER NOT NULL,
    outcome         TEXT NOT NULL,
    recorded_at     TEXT NOT NULL
);

CREATE INDEX idx_feedback_ctx_ge ON feedback_context(ge_id);
CREATE INDEX idx_feedback_ctx_issue ON feedback_context(issue_repo, issue_number);
