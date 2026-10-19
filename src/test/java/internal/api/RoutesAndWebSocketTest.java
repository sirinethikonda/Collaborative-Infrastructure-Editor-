package internal.api;

import cmd.editor.Main;
import com.fasterxml.jackson.databind.ObjectMapper;
import internal.api.Routes.CreateDocumentRequest;
import internal.api.Routes.CreateDocumentResponse;
import internal.api.Routes.RollbackRequest;
import internal.api.Routes.RollbackResponse;
import internal.ot.Operations.ClientMessage;
import internal.ot.Operations.Op;
import internal.storage.Replay;
import internal.storage.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Main.class)
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application-test.properties")
public class RoutesAndWebSocketTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Repository repository;

    @Autowired
    private Replay replay;

    @Autowired
    private WsHandler wsHandler;

    @Test
    public void testCreateDocumentEndpoint() throws Exception {
        CreateDocumentRequest req = new CreateDocumentRequest();
        req.name = "payments.yaml";
        req.content = "initial content";

        String json = objectMapper.writeValueAsString(req);

        String responseBody = mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("payments.yaml"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        CreateDocumentResponse res = objectMapper.readValue(responseBody, CreateDocumentResponse.class);
        assertNotNull(res.id);

        // Verify document in DB
        assertTrue(repository.findDocumentById(res.id).isPresent());

        // Verify initial operation in DB (rev 1)
        int latestRev = repository.getLatestRevision(res.id);
        assertEquals(1, latestRev);

        String content = replay.replayToRevision(res.id, 1);
        assertEquals("initial content", content);
    }

    @Test
    public void testRollbackEndpoint() throws Exception {
        // 1. Create document with initial content
        UUID docId = UUID.randomUUID();
        repository.insertDocument(docId, "rollback-test.yaml", java.time.Instant.now());
        repository.insertOperation(UUID.randomUUID(), docId, UUID.randomUUID(), 1, Op.insert(0, "line 1\n"), "{}", java.time.Instant.now(), "pending");

        // 2. Add operations to bring revision to 4
        repository.insertOperation(UUID.randomUUID(), docId, UUID.randomUUID(), 2, Op.insert(7, "line 2\n"), "{}", java.time.Instant.now(), "pending");
        repository.insertOperation(UUID.randomUUID(), docId, UUID.randomUUID(), 3, Op.insert(14, "line 3\n"), "{}", java.time.Instant.now(), "pending");
        repository.insertOperation(UUID.randomUUID(), docId, UUID.randomUUID(), 4, Op.insert(21, "line 4\n"), "{}", java.time.Instant.now(), "pending");

        String contentAtRev2 = replay.replayToRevision(docId, 2);

        // 3. Request rollback to revision 2
        RollbackRequest rollbackReq = new RollbackRequest();
        rollbackReq.targetRevision = 2;

        String responseBody = mockMvc.perform(post("/api/v1/documents/" + docId + "/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value(docId.toString()))
                .andExpect(jsonPath("$.target_revision").value(2))
                .andExpect(jsonPath("$.new_revision").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        RollbackResponse res = objectMapper.readValue(responseBody, RollbackResponse.class);
        assertTrue(res.newRevision > 4, "Rollback should append new compensating operations");

        // 4. Verify replay at new revision produces exact state as revision 2
        String rolledBackContent = replay.replayToRevision(docId, res.newRevision);
        assertEquals(contentAtRev2, rolledBackContent);
    }

    @Test
    public void testMetricsEndpoint() throws Exception {
        String metricsText = mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertNotNull(metricsText);
        // Verify all four required metric names are present
        assertTrue(metricsText.contains("edit_latency_ms"), "Metrics must include edit_latency_ms");
        assertTrue(metricsText.contains("transform_conflicts_total"), "Metrics must include transform_conflicts_total");
        assertTrue(metricsText.contains("validation_failures_total"), "Metrics must include validation_failures_total");
        assertTrue(metricsText.contains("snapshot_count"), "Metrics must include snapshot_count");
    }

    @Test
    public void testWebSocketHandlerDirectFlow() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        repository.insertDocument(docId, "ws-test.yaml", java.time.Instant.now());
        repository.insertOperation(UUID.randomUUID(), docId, userId, 1, Op.insert(0, "init"), "{}", java.time.Instant.now(), "pending");

        TestWebSocketSession session = new TestWebSocketSession("mock-session-1");
        wsHandler.afterConnectionEstablished(session);

        // Send an edit operation at revision 1
        ClientMessage clientMsg = new ClientMessage(docId, userId, 1, Op.insert(4, " added"));
        TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(clientMsg));

        wsHandler.handleTextMessage(session, textMessage);

        // Verify revision incremented to 2
        assertEquals(2, repository.getLatestRevision(docId));
        String updated = replay.replayLatest(docId);
        assertEquals("init added", updated);

        // Verify broadcast was received
        assertFalse(session.sentMessages.isEmpty());

        wsHandler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
    }

    @Test
    public void testConcurrentWebSocketOperationsConvergence() throws Exception {
        // Requirement 7:
        // Start with a document containing "apiVersion: v1".
        // Client A sends an insert operation at revision 1 to add " kind: Pod" at position 14.
        // Client B sends an insert operation at revision 1 to add " metadata:" at position 14.
        UUID docId = UUID.randomUUID();
        UUID userA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        repository.insertDocument(docId, "k8s.yaml", java.time.Instant.now());
        repository.insertOperation(UUID.randomUUID(), docId, userA, 1, Op.insert(0, "apiVersion: v1"), "{}", java.time.Instant.now(), "pending");

        TestWebSocketSession sessionA = new TestWebSocketSession("session-A");
        TestWebSocketSession sessionB = new TestWebSocketSession("session-B");
        wsHandler.afterConnectionEstablished(sessionA);
        wsHandler.afterConnectionEstablished(sessionB);

        // Client A sends at rev 1
        ClientMessage msgA = new ClientMessage(docId, userA, 1, Op.insert(14, " kind: Pod"));
        // Client B sends at rev 1
        ClientMessage msgB = new ClientMessage(docId, userB, 1, Op.insert(14, " metadata:"));

        wsHandler.handleTextMessage(sessionA, new TextMessage(objectMapper.writeValueAsString(msgA)));
        wsHandler.handleTextMessage(sessionB, new TextMessage(objectMapper.writeValueAsString(msgB)));

        // Verify that revisions 2 and 3 are stored in the database
        assertEquals(3, repository.getLatestRevision(docId));

        // Verify document state contains both operations
        String finalContent = replay.replayLatest(docId);
        assertTrue(finalContent.contains("kind: Pod"), "Final state must contain kind: Pod");
        assertTrue(finalContent.contains("metadata:"), "Final state must contain metadata:");

        wsHandler.afterConnectionClosed(sessionA, org.springframework.web.socket.CloseStatus.NORMAL);
        wsHandler.afterConnectionClosed(sessionB, org.springframework.web.socket.CloseStatus.NORMAL);
    }

    @Test
    public void testAutomaticSnapshotCreationOnTenRevisions() throws Exception {
        // Requirement 12:
        // Create a new document (rev 1).
        // Send operations to reach revision 10.
        // Verify a snapshot exists for revision 10 and content is correct.
        UUID docId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        repository.insertDocument(docId, "snapshot-test.yaml", java.time.Instant.now());
        repository.insertOperation(UUID.randomUUID(), docId, userId, 1, Op.insert(0, "Line 1\n"), "{}", java.time.Instant.now(), "pending");

        TestWebSocketSession session = new TestWebSocketSession("session-snapshot");
        wsHandler.afterConnectionEstablished(session);

        // Send 9 more operations to reach revision 10
        for (int r = 1; r < 10; r++) {
            ClientMessage msg = new ClientMessage(docId, userId, r, Op.insert(0, "Prefix " + r + " "));
            wsHandler.handleTextMessage(session, new TextMessage(objectMapper.writeValueAsString(msg)));
        }

        // Verify revision reached 10
        assertEquals(10, repository.getLatestRevision(docId));

        // Query snapshots table for that document_id at revision 10
        java.util.Optional<Repository.SnapshotRecord> snapshotOpt = repository.findLatestSnapshot(docId, 10);
        assertTrue(snapshotOpt.isPresent(), "Snapshot must exist for revision 10");
        assertEquals(10, snapshotOpt.get().revision);

        String currentContent = replay.replayLatest(docId);
        assertEquals(currentContent, snapshotOpt.get().content, "Snapshot content must match replayed content");

        wsHandler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
    }

    private static class TestWebSocketSession implements WebSocketSession {
        private final String id;
        private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        public final java.util.List<org.springframework.web.socket.WebSocketMessage<?>> sentMessages = new java.util.ArrayList<>();
        private boolean open = true;

        public TestWebSocketSession(String id) {
            this.id = id;
        }

        @Override public String getId() { return id; }
        @Override public java.net.URI getUri() { return null; }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() { return new org.springframework.http.HttpHeaders(); }
        @Override public java.util.Map<String, Object> getAttributes() { return attributes; }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() { return java.util.Collections.emptyList(); }
        @Override public synchronized void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) { sentMessages.add(message); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        @Override public void close(org.springframework.web.socket.CloseStatus status) { open = false; }
    }
}
