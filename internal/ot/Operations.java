package internal.ot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Operations {

    public enum Type {
        insert,
        delete,
        noop
    }

    public static class Op {
        @JsonProperty("type")
        private String type;

        @JsonProperty("position")
        private int position;

        @JsonProperty("value")
        private String value;

        @JsonProperty("length")
        private Integer length;

        public Op() {
        }

        public Op(String type, int position, String value, Integer length) {
            this.type = type;
            this.position = position;
            this.value = value;
            this.length = length;
        }

        public static Op insert(int position, String value) {
            return new Op("insert", position, value, null);
        }

        public static Op delete(int position, int length) {
            return new Op("delete", position, null, length);
        }

        public static Op noop() {
            return new Op("noop", 0, "", 0);
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public Integer getLength() {
            if (length != null) {
                return length;
            }
            if (value != null) {
                return value.length();
            }
            return 0;
        }

        public void setLength(Integer length) {
            this.length = length;
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isInsert() {
            return "insert".equalsIgnoreCase(type);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isDelete() {
            return "delete".equalsIgnoreCase(type);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isNoop() {
            return "noop".equalsIgnoreCase(type) ||
                   (isInsert() && (value == null || value.isEmpty())) ||
                   (isDelete() && (getLength() <= 0));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Op op = (Op) o;
            return position == op.position &&
                    Objects.equals(type, op.type) &&
                    Objects.equals(value, op.value) &&
                    Objects.equals(getLength(), op.getLength());
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, position, value, getLength());
        }

        @Override
        public String toString() {
            return "Op{" +
                    "type='" + type + '\'' +
                    ", position=" + position +
                    ", value='" + value + '\'' +
                    ", length=" + length +
                    '}';
        }
    }

    public static class ClientMessage {
        @JsonProperty("document_id")
        private UUID documentId;

        @JsonProperty("user_id")
        private UUID userId;

        @JsonProperty("revision")
        private int revision;

        @JsonProperty("operation")
        private Op operation;

        public ClientMessage() {
        }

        public ClientMessage(UUID documentId, UUID userId, int revision, Op operation) {
            this.documentId = documentId;
            this.userId = userId;
            this.revision = revision;
            this.operation = operation;
        }

        public UUID getDocumentId() {
            return documentId;
        }

        public void setDocumentId(UUID documentId) {
            this.documentId = documentId;
        }

        public UUID getUserId() {
            return userId;
        }

        public void setUserId(UUID userId) {
            this.userId = userId;
        }

        public int getRevision() {
            return revision;
        }

        public void setRevision(int revision) {
            this.revision = revision;
        }

        public Op getOperation() {
            return operation;
        }

        public void setOperation(Op operation) {
            this.operation = operation;
        }
    }

    public static class ServerBroadcast {
        @JsonProperty("document_id")
        private UUID documentId;

        @JsonProperty("revision")
        private int revision;

        @JsonProperty("transformed_operation")
        private Op transformedOperation;

        @JsonProperty("content_hash")
        private String contentHash;

        public ServerBroadcast() {
        }

        public ServerBroadcast(UUID documentId, int revision, Op transformedOperation, String contentHash) {
            this.documentId = documentId;
            this.revision = revision;
            this.transformedOperation = transformedOperation;
            this.contentHash = contentHash;
        }

        public UUID getDocumentId() {
            return documentId;
        }

        public void setDocumentId(UUID documentId) {
            this.documentId = documentId;
        }

        public int getRevision() {
            return revision;
        }

        public void setRevision(int revision) {
            this.revision = revision;
        }

        public Op getTransformedOperation() {
            return transformedOperation;
        }

        public void setTransformedOperation(Op transformedOperation) {
            this.transformedOperation = transformedOperation;
        }

        public String getContentHash() {
            return contentHash;
        }

        public void setContentHash(String contentHash) {
            this.contentHash = contentHash;
        }
    }
}
