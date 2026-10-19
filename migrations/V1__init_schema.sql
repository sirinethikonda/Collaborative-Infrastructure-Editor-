CREATE TABLE documents (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE operations (
    id UUID PRIMARY KEY,
    document_id UUID REFERENCES documents(id),
    user_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    operation_payload JSONB NOT NULL,
    vector_clock JSONB NOT NULL, -- Optional but recommended for advanced OT
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE snapshots (
    document_id UUID REFERENCES documents(id),
    revision INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(document_id, revision)
);
