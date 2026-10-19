package internal.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private final Repository repository;

    public SnapshotService(Repository repository) {
        this.repository = repository;
    }

    /**
     * Checks if a snapshot should be created at the given revision (every 10 revisions).
     * If so, stores the full content at that revision in the snapshots table.
     */
    public boolean checkAndCreateSnapshot(UUID documentId, int revision, String content) {
        if (revision > 0 && revision % 10 == 0) {
            try {
                repository.insertSnapshot(documentId, revision, content, Instant.now());
                log.info("Created snapshot for document {} at revision {}", documentId, revision);
                return true;
            } catch (Exception e) {
                log.error("Failed to create snapshot for document {} at revision {}", documentId, revision, e);
            }
        }
        return false;
    }
}
