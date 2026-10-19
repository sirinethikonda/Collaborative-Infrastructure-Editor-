package internal.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import internal.ot.Operations.ClientMessage;
import internal.ot.Operations.Op;
import internal.ot.Operations.ServerBroadcast;
import internal.ot.Transform;
import internal.storage.Replay;
import internal.storage.Repository;
import internal.storage.Repository.OperationRecord;
import internal.storage.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class WsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);

    private final Repository repository;
    private final Replay replay;
    private final SnapshotService snapshotService;
    private final MetricsConfig metricsConfig;
    private final ObjectMapper objectMapper;

    // Active WebSocket sessions
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    // Map each session to its active document ID
    private final Map<String, UUID> sessionDocumentMap = new ConcurrentHashMap<>();

    // Synchronization locks per document to prevent race conditions during OT
    private final Map<UUID, ReentrantLock> documentLocks = new ConcurrentHashMap<>();

    public WsHandler(Repository repository,
                     Replay replay,
                     SnapshotService snapshotService,
                     MetricsConfig metricsConfig,
                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.replay = replay;
        this.snapshotService = snapshotService;
        this.metricsConfig = metricsConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket connection established: session id={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        sessionDocumentMap.remove(session.getId());
        log.info("WebSocket connection closed: session id={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        long startTime = System.nanoTime();
        String payload = message.getPayload();
        log.debug("Received WebSocket message: {}", payload);

        try {
            ClientMessage clientMsg = objectMapper.readValue(payload, ClientMessage.class);
            UUID docId = clientMsg.getDocumentId();
            UUID userId = clientMsg.getUserId();
            int clientRev = clientMsg.getRevision();
            Op incomingOp = clientMsg.getOperation();

            if (docId == null || incomingOp == null) {
                log.warn("Invalid message missing document_id or operation: {}", payload);
                return;
            }

            sessionDocumentMap.put(session.getId(), docId);

            ReentrantLock lock = documentLocks.computeIfAbsent(docId, k -> new ReentrantLock());
            lock.lock();
            try {
                // 1. Get current latest revision on server
                int serverLatestRev = repository.getLatestRevision(docId);
                Op transformedOp = incomingOp;

                // 2. If client is behind server, transform against all intervening operations
                if (clientRev < serverLatestRev) {
                    metricsConfig.getTransformConflictsCounter().increment();
                    List<OperationRecord> concurrentOps = repository.getOperationsBetween(docId, clientRev, serverLatestRev);
                    for (OperationRecord priorOp : concurrentOps) {
                        transformedOp = Transform.transformSingle(transformedOp, priorOp.operationPayload, userId, priorOp.userId);
                    }
                }

                // 3. Increment revision and persist the operation
                int newRev = serverLatestRev + 1;
                Instant now = Instant.now();
                UUID opId = UUID.randomUUID();

                repository.insertOperation(
                        opId,
                        docId,
                        userId,
                        newRev,
                        transformedOp,
                        "{}",
                        now,
                        "pending"
                );

                // 4. Reconstruct document state to compute hash and check snapshot
                String updatedContent = replay.replayToRevision(docId, newRev);
                String contentHash = Replay.computeHash(updatedContent);

                // 5. Automatic snapshotting every 10 revisions
                snapshotService.checkAndCreateSnapshot(docId, newRev, updatedContent);

                // 6. Broadcast outgoing message to all connected clients
                ServerBroadcast broadcast = new ServerBroadcast(
                        docId,
                        newRev,
                        transformedOp,
                        contentHash
                );
                String broadcastJson = objectMapper.writeValueAsString(broadcast);
                TextMessage broadcastMsg = new TextMessage(broadcastJson);

                broadcastToClients(docId, broadcastMsg);

            } finally {
                lock.unlock();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                metricsConfig.getEditLatencyTimer().record(durationMs, TimeUnit.MILLISECONDS);
            }

        } catch (Exception e) {
            log.error("Error processing WebSocket message", e);
            try {
                session.sendMessage(new TextMessage("{\"error\": \"" + e.getMessage() + "\"}"));
            } catch (IOException ignored) {
            }
        }
    }

    private void broadcastToClients(UUID docId, TextMessage msg) {
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                UUID sessionDoc = sessionDocumentMap.get(s.getId());
                // Broadcast to sessions editing this doc or general sessions
                if (sessionDoc == null || sessionDoc.equals(docId)) {
                    try {
                        synchronized (s) {
                            s.sendMessage(msg);
                        }
                    } catch (IOException e) {
                        log.error("Failed to send message to session {}", s.getId(), e);
                    }
                }
            }
        }
    }
}
