CREATE TABLE IF NOT EXISTS jobs (
    id          VARCHAR(36) PRIMARY KEY,
    status      VARCHAR(16) NOT NULL,
    file_name   TEXT,
    file_size   BIGINT,
    created_at  TIMESTAMP NOT NULL,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP,
    error       TEXT,
    summary     TEXT
);
CREATE INDEX IF NOT EXISTS jobs_created_idx ON jobs (created_at DESC);
