package internal.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import internal.ot.Operations.Op;
import internal.storage.Replay;
import internal.storage.Repository;
import internal.storage.SnapshotService;
import validation.Runner;
import validation.Runner.ValidationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class Routes {

    private final Repository repository;
    private final Replay replay;
    private final SnapshotService snapshotService;
    private final Runner validationRunner;
    private final MetricsConfig metricsConfig;

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public Routes(Repository repository,
                  Replay replay,
                  SnapshotService snapshotService,
                  Runner validationRunner,
                  MetricsConfig metricsConfig) {
        this.repository = repository;
        this.replay = replay;
        this.snapshotService = snapshotService;
        this.validationRunner = validationRunner;
        this.metricsConfig = metricsConfig;
    }

    // --- Create Document DTOs ---
    public static class CreateDocumentRequest {
        public String name;
        public String content;
    }

    public static class CreateDocumentResponse {
        @JsonProperty("id")
        public UUID id;

        @JsonProperty("name")
        public String name;

        @JsonProperty("createdAt")
        public String createdAt;

        public CreateDocumentResponse(UUID id, String name, String createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
        }
    }

    @PostMapping("/documents")
    public ResponseEntity<CreateDocumentResponse> createDocument(@RequestBody CreateDocumentRequest req) {
        UUID docId = UUID.randomUUID();
        Instant now = Instant.now();
        String name = (req.name != null && !req.name.isEmpty()) ? req.name : "manifest.yaml";
        String initialContent = (req.content != null) ? req.content : "";

        // 1. Insert document row
        repository.insertDocument(docId, name, now);

        // 2. Insert initial operation at revision 1
        Op initialOp = Op.insert(0, initialContent);
        repository.insertOperation(
                UUID.randomUUID(),
                docId,
                SYSTEM_USER_ID,
                1,
                initialOp,
                "{}",
                now,
                "pending"
        );

        CreateDocumentResponse res = new CreateDocumentResponse(docId, name, now.toString());
        return new ResponseEntity<>(res, HttpStatus.CREATED);
    }

    // --- Commit Document DTOs ---
    public static class CommitResponse {
        public String status;
        @JsonProperty("validation_output")
        public String validationOutput;

        public CommitResponse(String status, String validationOutput) {
            this.status = status;
            this.validationOutput = validationOutput;
        }
    }

    @PostMapping("/documents/{id}/commit")
    public ResponseEntity<CommitResponse> commitDocument(@PathVariable("id") UUID id) {
        // Replay document content up to current revision
        String content = replay.replayLatest(id);

        // Run validation pipeline
        ValidationResult result = validationRunner.validateManifest(content);

        if (result.isSuccess()) {
            repository.updateValidationStatusForPending(id, "validated");
            return ResponseEntity.ok(new CommitResponse("validated", result.getOutput()));
        } else {
            metricsConfig.getValidationFailuresCounter().increment();
            repository.updateValidationStatusForPending(id, "failed");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new CommitResponse("failed", result.getOutput()));
        }
    }

    // --- Rollback Document DTOs ---
    public static class RollbackRequest {
        @JsonProperty("target_revision")
        public int targetRevision;
    }

    public static class RollbackResponse {
        @JsonProperty("document_id")
        public UUID documentId;

        @JsonProperty("new_revision")
        public int newRevision;

        @JsonProperty("target_revision")
        public int targetRevision;

        public RollbackResponse(UUID documentId, int newRevision, int targetRevision) {
            this.documentId = documentId;
            this.newRevision = newRevision;
            this.targetRevision = targetRevision;
        }
    }

    @PostMapping("/documents/{id}/rollback")
    public ResponseEntity<?> rollbackDocument(@PathVariable("id") UUID id, @RequestBody RollbackRequest req) {
        int currentRev = repository.getLatestRevision(id);
        int targetRev = req.targetRevision;

        if (targetRev < 1 || targetRev > currentRev) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid target revision: " + targetRev));
        }

        String targetContent = replay.replayToRevision(id, targetRev);
        String currentContent = replay.replayToRevision(id, currentRev);

        int newRev = currentRev;

        if (!currentContent.equals(targetContent)) {
            // Revert by appending compensating operations:
            // 1. Delete all current characters if currentContent is not empty
            if (currentContent.length() > 0) {
                newRev++;
                Op delOp = Op.delete(0, currentContent.length());
                repository.insertOperation(
                        UUID.randomUUID(),
                        id,
                        SYSTEM_USER_ID,
                        newRev,
                        delOp,
                        "{}",
                        Instant.now(),
                        "pending"
                );
            }

            // 2. Insert targetContent if not empty
            if (targetContent.length() > 0) {
                newRev++;
                Op insOp = Op.insert(0, targetContent);
                repository.insertOperation(
                        UUID.randomUUID(),
                        id,
                        SYSTEM_USER_ID,
                        newRev,
                        insOp,
                        "{}",
                        Instant.now(),
                        "pending"
                );
            }

            snapshotService.checkAndCreateSnapshot(id, newRev, targetContent);
        }

        return ResponseEntity.ok(new RollbackResponse(id, newRev, targetRev));
    }
}
