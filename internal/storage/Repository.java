package internal.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import internal.ot.Operations.Op;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Component
public class Repository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Repository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public static class DocumentRecord {
        public UUID id;
        public String name;
        public Instant createdAt;

        public DocumentRecord(UUID id, String name, Instant createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
        }
    }

    public static class OperationRecord {
        public UUID id;
        public UUID documentId;
        public UUID userId;
        public int revision;
        public Op operationPayload;
        public String vectorClock;
        public Instant createdAt;
        public String validationStatus;

        public OperationRecord() {
        }

        public OperationRecord(UUID id, UUID documentId, UUID userId, int revision, Op operationPayload,
                               String vectorClock, Instant createdAt, String validationStatus) {
            this.id = id;
            this.documentId = documentId;
            this.userId = userId;
            this.revision = revision;
            this.operationPayload = operationPayload;
            this.vectorClock = vectorClock;
            this.createdAt = createdAt;
            this.validationStatus = validationStatus;
        }
    }

    public static class SnapshotRecord {
        public UUID documentId;
        public int revision;
        public String content;
        public Instant createdAt;

        public SnapshotRecord(UUID documentId, int revision, String content, Instant createdAt) {
            this.documentId = documentId;
            this.revision = revision;
            this.content = content;
            this.createdAt = createdAt;
        }
    }

    public void insertDocument(UUID id, String name, Instant createdAt) {
        String sql = "INSERT INTO documents (id, name, created_at) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, id, name, Timestamp.from(createdAt));
    }

    public Optional<DocumentRecord> findDocumentById(UUID id) {
        String sql = "SELECT id, name, created_at FROM documents WHERE id = ?";
        try {
            DocumentRecord doc = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                    new DocumentRecord(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toInstant()
                    ), id);
            return Optional.ofNullable(doc);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertOperation(UUID id, UUID documentId, UUID userId, int revision,
                                Op payload, String vectorClockJson, Instant createdAt, String validationStatus) {
        String sql = "INSERT INTO operations (id, document_id, user_id, revision, operation_payload, vector_clock, created_at, validation_status) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)";
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            payloadJson = "{}";
        }
        if (vectorClockJson == null || vectorClockJson.isEmpty()) {
            vectorClockJson = "{}";
        }
        if (validationStatus == null) {
            validationStatus = "pending";
        }
        jdbcTemplate.update(sql,
                id,
                documentId,
                userId,
                revision,
                payloadJson,
                vectorClockJson,
                Timestamp.from(createdAt),
                validationStatus);
    }

    public int getLatestRevision(UUID documentId) {
        String sql = "SELECT COALESCE(MAX(revision), 0) FROM operations WHERE document_id = ?";
        Integer rev = jdbcTemplate.queryForObject(sql, Integer.class, documentId);
        return (rev != null) ? rev : 0;
    }

    public List<OperationRecord> getOperationsBetween(UUID documentId, int startExclusive, int endInclusive) {
        String sql = "SELECT id, document_id, user_id, revision, operation_payload::text as payload_text, " +
                "vector_clock::text as vc_text, created_at, validation_status " +
                "FROM operations WHERE document_id = ? AND revision > ? AND revision <= ? " +
                "ORDER BY revision ASC";
        return jdbcTemplate.query(sql, new OperationRowMapper(objectMapper), documentId, startExclusive, endInclusive);
    }

    public List<OperationRecord> getOperationsSince(UUID documentId, int revisionExclusive) {
        String sql = "SELECT id, document_id, user_id, revision, operation_payload::text as payload_text, " +
                "vector_clock::text as vc_text, created_at, validation_status " +
                "FROM operations WHERE document_id = ? AND revision > ? " +
                "ORDER BY revision ASC";
        return jdbcTemplate.query(sql, new OperationRowMapper(objectMapper), documentId, revisionExclusive);
    }

    public Optional<SnapshotRecord> findLatestSnapshot(UUID documentId, int maxRevision) {
        String sql = "SELECT document_id, revision, content, created_at FROM snapshots " +
                "WHERE document_id = ? AND revision <= ? " +
                "ORDER BY revision DESC LIMIT 1";
        try {
            SnapshotRecord snapshot = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                    new SnapshotRecord(
                            UUID.fromString(rs.getString("document_id")),
                            rs.getInt("revision"),
                            rs.getString("content"),
                            rs.getTimestamp("created_at").toInstant()
                    ), documentId, maxRevision);
            return Optional.ofNullable(snapshot);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertSnapshot(UUID documentId, int revision, String content, Instant createdAt) {
        String checkSql = "SELECT COUNT(*) FROM snapshots WHERE document_id = ? AND revision = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, documentId, revision);
        if (count != null && count > 0) {
            String updateSql = "UPDATE snapshots SET content = ?, created_at = ? WHERE document_id = ? AND revision = ?";
            jdbcTemplate.update(updateSql, content, Timestamp.from(createdAt), documentId, revision);
        } else {
            String insertSql = "INSERT INTO snapshots (document_id, revision, content, created_at) VALUES (?, ?, ?, ?)";
            jdbcTemplate.update(insertSql, documentId, revision, content, Timestamp.from(createdAt));
        }
    }

    public int countTotalSnapshots() {
        String sql = "SELECT COUNT(*) FROM snapshots";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return (count != null) ? count : 0;
    }

    public int updateValidationStatusForPending(UUID documentId, String newStatus) {
        String sql = "UPDATE operations SET validation_status = ? WHERE document_id = ? AND validation_status = 'pending'";
        return jdbcTemplate.update(sql, newStatus, documentId);
    }

    private static class OperationRowMapper implements RowMapper<OperationRecord> {
        private final ObjectMapper mapper;

        public OperationRowMapper(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public OperationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = UUID.fromString(rs.getString("id"));
            UUID docId = UUID.fromString(rs.getString("document_id"));
            UUID userId = UUID.fromString(rs.getString("user_id"));
            int revision = rs.getInt("revision");
            String payloadText = rs.getString("payload_text");
            String vcText = rs.getString("vc_text");
            Instant createdAt = rs.getTimestamp("created_at").toInstant();
            String status = rs.getString("validation_status");

            Op op = null;
            if (payloadText != null) {
                try {
                    payloadText = payloadText.trim();
                    if (payloadText.startsWith("\"") && payloadText.endsWith("\"")) {
                        payloadText = mapper.readValue(payloadText, String.class);
                    }
                    op = mapper.readValue(payloadText, Op.class);
                } catch (Exception e) {
                    System.err.println("Failed to parse Op from payloadText: '" + payloadText + "': " + e.getMessage());
                    op = Op.noop();
                }
            }

            return new OperationRecord(id, docId, userId, revision, op, vcText, createdAt, status);
        }
    }
}
