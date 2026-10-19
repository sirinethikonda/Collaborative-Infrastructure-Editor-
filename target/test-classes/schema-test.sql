CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS operations (
    id UUID PRIMARY KEY,
    document_id UUID REFERENCES documents(id),
    user_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    operation_payload JSON NOT NULL,
    vector_clock JSON NOT NULL,
    created_at TIMESTAMP NOT NULL,
    validation_status TEXT DEFAULT 'pending'
);

CREATE TABLE IF NOT EXISTS snapshots (
    document_id UUID REFERENCES documents(id),
    revision INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(document_id, revision)
);
