package internal.storage;

import internal.ot.Transform;
import internal.storage.Repository.OperationRecord;
import internal.storage.Repository.SnapshotRecord;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class Replay {

    private final Repository repository;

    public Replay(Repository repository) {
        this.repository = repository;
    }

    /**
     * Replays all operations for the document up to targetRevision.
     * Leverages the latest snapshot <= targetRevision for performance.
     */
    public String replayToRevision(UUID documentId, int targetRevision) {
        if (targetRevision <= 0) {
            return "";
        }

        // Look for the nearest snapshot <= targetRevision
        Optional<SnapshotRecord> snapshotOpt = repository.findLatestSnapshot(documentId, targetRevision);
        String currentContent = "";
        int startRev = 0;

        if (snapshotOpt.isPresent()) {
            SnapshotRecord snapshot = snapshotOpt.get();
            currentContent = snapshot.content;
            startRev = snapshot.revision;
        }

        if (startRev < targetRevision) {
            List<OperationRecord> ops = repository.getOperationsBetween(documentId, startRev, targetRevision);
            for (OperationRecord opRecord : ops) {
                currentContent = Transform.apply(currentContent, opRecord.operationPayload);
            }
        }

        return currentContent;
    }

    /**
     * Replays all operations for the document to current latest revision.
     */
    public String replayLatest(UUID documentId) {
        int latestRev = repository.getLatestRevision(documentId);
        return replayToRevision(documentId, latestRev);
    }

    /**
     * Computes the SHA-256 hex digest of the given content.
     */
    public static String computeHash(String content) {
        if (content == null) {
            content = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
