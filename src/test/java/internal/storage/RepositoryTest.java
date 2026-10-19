package internal.storage;

import cmd.editor.Main;
import internal.ot.Operations.Op;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = Main.class)
@TestPropertySource(locations = "classpath:application-test.properties")
public class RepositoryTest {

    @Autowired
    private Repository repository;

    @Autowired
    private Replay replay;

    @Autowired
    private SnapshotService snapshotService;

    @Test
    public void testDocumentAndOperationLifecycle() {
        UUID docId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Insert document
        repository.insertDocument(docId, "test.yaml", now);
        Optional<Repository.DocumentRecord> docOpt = repository.findDocumentById(docId);
        assertTrue(docOpt.isPresent());
        assertEquals("test.yaml", docOpt.get().name);

        // 2. Insert initial operation at rev 1
        Op op1 = Op.insert(0, "apiVersion: v1\n");
        repository.insertOperation(UUID.randomUUID(), docId, userId, 1, op1, "{}", now, "pending");

        // 3. Insert rev 2
        Op op2 = Op.insert(15, "kind: Pod\n");
        repository.insertOperation(UUID.randomUUID(), docId, userId, 2, op2, "{}", now, "pending");

        // 4. Verify replay
        String content = replay.replayToRevision(docId, 2);
        System.out.println("Replayed content: [" + content + "]");
        assertTrue(content.contains("apiVersion: v1"));
        assertTrue(content.contains("kind: Pod"));

        // 5. Test snapshotting
        snapshotService.checkAndCreateSnapshot(docId, 10, content);
    }
}
