CREATE TABLE provenance (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    issue_repo TEXT NOT NULL,
    issue_number INTEGER NOT NULL,
    ge_id TEXT NOT NULL,
    spec_name TEXT NOT NULL DEFAULT '',
    recorded_at TEXT NOT NULL,
    recorded_by TEXT,
    UNIQUE(issue_repo, issue_number, ge_id)
);

CREATE INDEX idx_provenance_issue ON provenance(issue_repo, issue_number);
CREATE INDEX idx_provenance_ge ON provenance(ge_id);
