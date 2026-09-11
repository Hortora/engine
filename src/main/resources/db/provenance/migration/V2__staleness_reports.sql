CREATE TABLE staleness_reports (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ge_id           TEXT NOT NULL,
    stack           TEXT NOT NULL,
    reported_at     TEXT NOT NULL,
    reported_by     TEXT
);

CREATE INDEX idx_staleness_ge ON staleness_reports(ge_id);
CREATE INDEX idx_staleness_stack ON staleness_reports(stack);
