# Architecture and Design Document: Collaborative Infrastructure Editor

## 1. System Overview

The **Collaborative Infrastructure Editor** is a distributed, real-time collaborative system designed for editing Cloud Native infrastructure manifests (such as Kubernetes YAML and Terraform HCL). It brings together three core software engineering patterns:

1. **Operational Transformation (OT)** for conflict-free, concurrent multi-user editing.
2. **Event Sourcing** with PostgreSQL for an immutable audit trail and point-in-time state recovery.
3. **Decoupled Validation Pipelines** invoking external linters and tools (such as `kubectl apply --dry-run=client`) to guard configuration correctness before deployment.

---

## 2. System Architecture

```
                  +----------------------------------------------+
                  |               Client Sessions                |
                  |  (Web browsers / terminals / API consumers)  |
                  +----------------------------------------------+
                                |                    |
                     WebSocket (/ws)           HTTP (/api/v1)
                                |                    |
+-----------------------------------------------------------------------------------+
| Spring Boot Application (infra-editor)                                            |
|                                                                                   |
|  [internal/api]                                                                   |
|   - WsHandler: Manages connections, OT sync queue, and fan-out broadcasts         |
|   - Routes: /documents, /commit, /rollback, /metrics                              |
|   - MetricsConfig: Prometheus instrumentation                                     |
|                                                                                   |
|  [internal/ot]                                                                    |
|   - Transform: Pure mathematical OT engine T(opA, opB) -> (opA', opB')            |
|   - Operations: Insert, Delete, and compound message models                       |
|                                                                                   |
|  [internal/storage]                                                               |
|   - Replay: Reconstructs document state from snapshot + subsequent events         |
|   - SnapshotService: Periodically checkpoints full document state                 |
|   - Repository: Interacts with PostgreSQL using optimistic concurrency            |
|                                                                                   |
|  [validation]                                                                     |
|   - Runner: Isolates subprocess execution (kubectl apply --dry-run=client)        |
+-----------------------------------------------------------------------------------+
                               |                     |
                       PostgreSQL (5432)       Subprocess (CLI)
                               |                     |
                   +------------------------+  +-------------------+
                   | documents              |  | kubectl           |
                   | operations (Event Log) |  | dry-run runner    |
                   | snapshots (Checkpoints)|  +-------------------+
                   +------------------------+
```

---

## 3. Operational Transformation (OT) Design

### 3.1 Operation Representation
Text mutations are represented as discrete operations:
- **Insert**: `Insert(pos, val)` inserts character sequence `val` starting at index `pos`.
- **Delete**: `Delete(pos, len)` removes `len` characters beginning at index `pos`.

### 3.2 Transformation Function $T(op_A, op_B)$
When two clients concurrently generate operations $op_A$ and $op_B$ against the same revision $R$, neither client has seen the other's operation. When the server applies $op_A$ first, $op_B$ must be transformed against $op_A$ into $op_B'$ such that:

$$apply(apply(S, op_A), op_B') = apply(apply(S, op_B), op_A')$$

This property is **Transformation Property 1 (TP1)**, which guarantees convergence.

#### 1. Insert vs. Insert
Given $op_1 = Insert(p_1, v_1)$ and $op_2 = Insert(p_2, v_2)$ with lengths $L_1, L_2$:
- **$p_1 < p_2$**: $op_1$ occurs before $op_2$. $op_1' = Insert(p_1, v_1)$, while $op_2$ shifts right: $op_2' = Insert(p_2 + L_1, v_2)$.
- **$p_1 > p_2$**: $op_1$ shifts right: $op_1' = Insert(p_1 + L_2, v_1)$, while $op_2' = Insert(p_2, v_2)$.
- **$p_1 == p_2$ (Simultaneous Insertion at Exact Index)**:
  To eliminate ambiguity, a deterministic tie-breaker is applied using the lexicographical ordering of author `user_id`:
  $$\text{If } user_1 < user_2 \implies op_1 \text{ takes precedence; } op_2' = Insert(p_2 + L_1, v_2)$$
  $$\text{If } user_1 > user_2 \implies op_2 \text{ takes precedence; } op_1' = Insert(p_1 + L_2, v_1)$$
  This guarantees that regardless of which operation reaches the server first, both operations are ordered identically in the final text.

#### 2. Insert vs. Delete
Given $ins = Insert(p_i, v_i)$ and $del = Delete(p_d, L_d)$:
- **$p_i \le p_d$**: The insertion precedes the deletion range. $ins' = Insert(p_i, v_i)$, while $del$ shifts right: $del' = Delete(p_d + L_i, L_d)$.
- **$p_i \ge p_d + L_d$**: The insertion follows the deletion range. $del' = Delete(p_d, L_d)$, while $ins$ shifts left: $ins' = Insert(p_i - L_d, v_i)$.
- **$p_d < p_i < p_d + L_d$**: The insertion occurred inside the region deleted concurrently. The insert is adjusted to start at $p_d$: $ins' = Insert(p_d, v_i)$.

#### 3. Delete vs. Delete
Given $del_1 = Delete(p_1, L_1)$ and $del_2 = Delete(p_2, L_2)$:
- **Disjoint**: If $p_1 + L_1 \le p_2$, $del_1$ is before $del_2$. $del_2$ shifts left by $L_1$.
- **Overlapping**: If $del_2$ completely encloses $del_1$, $del_1'$ becomes a `noop` to prevent double-deletion. If $del_1$ partially overlaps $del_2$, the boundaries are adjusted so only remaining characters are removed.

---

## 4. Event Sourcing & CQRS

### 4.1 Write Model: Immutable Operations Stream
The `operations` table represents an append-only event log. Every mutation receives a strictly monotonic revision number:
- Rows are never modified or deleted.
- Vector clocks or sequence revisions are stored alongside each payload.
- Optimistic locking and per-document synchronization serialize concurrent writes.

### 4.2 Read Model: Snapshots and Replay Acceleration
Replaying thousands of individual operations to compute current text incurs $O(N)$ overhead. To ensure constant or bounded latency:
1. **Periodic Snapshots**: When `revision % 10 == 0`, a full snapshot of the document text is persisted in `snapshots(document_id, revision, content, created_at)`.
2. **Accelerated Replay**:
   - Query the greatest snapshot where `revision <= target_revision`.
   - Replay only the subsequent delta operations (`start_revision < revision <= target_revision`).

### 4.3 Rollback Strategy via Compensating Operations
In accordance with Event Sourcing principles, rolling back to an earlier revision (e.g., from revision 7 to revision 4) **must not delete history**:
1. Reconstruct document state at revision 4 ($S_4$) and revision 7 ($S_7$).
2. Compute the compensating delta:
   - Delete all $L(S_7)$ characters at position 0 (revision 8).
   - Insert $S_4$ at position 0 (revision 9).
3. Append these operations to the log.
Replaying the log from revision 1 through 9 produces an exact byte-for-byte representation of revision 4, maintaining an unalterable audit trail.

---

## 5. Validation Pipeline

The validation runner ensures infrastructure manifests adhere to Kubernetes specifications:
1. Upon receiving `POST /api/v1/documents/{id}/commit`, the service reconstructs the current document state.
2. The manifest is written to a secure temporary file.
3. The runner spawns `kubectl apply --dry-run=client -f <temp_file>` in an isolated subprocess with a 10-second timeout.
4. Output and exit code are evaluated:
   - **Exit 0**: Manifest is structurally and syntactically valid. `validation_status` for all pending operations is updated to `'validated'`. Returns HTTP `200 OK`.
   - **Exit non-zero**: Manifest is invalid. `validation_failures_total` counter is incremented, and `validation_status` is marked `'failed'`. Returns HTTP `400 Bad Request`.
5. The temporary file is cleaned up in a `finally` block to prevent resource leakage.

---

## 6. Observability & Prometheus Metrics

The system exposes metrics at `GET /metrics` in standard Prometheus text format:
- `edit_latency_ms`: Summary / Histogram recording end-to-end OT transformation and broadcast latency.
- `transform_conflicts_total`: Counter tracking the total number of concurrent edits that required operational transformation.
- `validation_failures_total`: Counter tracking failed manifest validation attempts.
- `snapshot_count`: Gauge tracking total snapshot records in PostgreSQL.
