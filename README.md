# Collaborative Infrastructure Editor with OT & Event Sourcing

A high-performance, real-time collaborative editor for infrastructure manifests (Kubernetes YAML, Terraform HCL) built with **Java 17/21**, **Spring Boot 3**, and **PostgreSQL**.

The platform incorporates:
- **Operational Transformation (OT)** for conflict-free, concurrent multi-user editing.
- **Event Sourcing** with PostgreSQL for an immutable append-only audit trail and point-in-time state recovery.
- **Automated Checkpoints (Snapshots)** generated every 10 revisions for fast document reconstruction.
- **Validation Pipeline** integrating `kubectl apply --dry-run=client` to verify Kubernetes manifests before committing.
- **Compensating Rollback** to restore previous document states without mutating historical log records.
- **Prometheus Metrics** on `/metrics` tracking transformation latency, conflicts, validation failures, and snapshot counts.

---

## Repository Structure

```
infra-editor/
  cmd/
    editor/
      Main.java                     # Application entrypoint
  internal/
    ot/
      Operations.java               # OT operation models & DTOs
      Transform.java                # Pure OT transformation logic
    api/
      WsHandler.java                # WebSocket handler (/ws) with OT synchronization
      Routes.java                   # REST endpoints (/documents, /commit, /rollback)
      WebSocketConfig.java          # WebSocket endpoint registration
      MetricsConfig.java            # Prometheus instrumentation (/metrics)
    storage/
      Repository.java               # Database repository for documents, operations, snapshots
      Replay.java                   # Event replay & snapshot acceleration engine
      SnapshotService.java          # Snapshot persistence service (every 10 revisions)
  validation/
    Runner.java                     # Subprocess runner for kubectl dry-run validation
  migrations/
    V1__init_schema.sql             # Initial schema: documents, operations, snapshots
    V2__add_validation_status.sql   # Adds validation_status column
  docs/
    design.md                       # Comprehensive design & OT algorithm documentation
  README.md                         # Project documentation
  docker-compose.yml                # Docker compose orchestration with healthchecks
  Dockerfile                        # Multi-stage container build with kubectl installed
  .env.example                      # Environment variables template
  pom.xml                           # Maven project configuration
```

---

## Quickstart with Docker Compose

Ensure Docker and Docker Compose are installed and running on your system.

1. **Clone and copy the environment variables:**
   ```bash
   cp .env.example .env
   ```

2. **Start the stack:**
   ```bash
   docker-compose up --build -d
   ```

3. **Verify running containers and health:**
   ```bash
   docker-compose ps
   ```
   Both the `app` and `db` containers will start, run automatic database migrations, and become healthy.

---

## API Documentation

### 1. Create Document
Creates a new document and writes the initial insert operation at **revision 1**.

- **URL:** `POST /api/v1/documents`
- **Request Body:**
  ```json
  {
    "name": "payments.yaml",
    "content": "apiVersion: v1\nkind: Pod\nmetadata:\n  name: payments\n"
  }
  ```
- **Response (201 Created):**
  ```json
  {
    "id": "e0a4f6cf-71d3-4927-b9c1-508b5f3dd2f7",
    "name": "payments.yaml",
    "createdAt": "2026-10-19T08:30:00Z"
  }
  ```

---

### 2. WebSocket Real-Time Editing
Connect to `ws://<host>:<port>/ws` to exchange real-time operations.

- **Incoming Message (Client -> Server):**
  ```json
  {
    "document_id": "e0a4f6cf-71d3-4927-b9c1-508b5f3dd2f7",
    "user_id": "11111111-1111-1111-1111-111111111111",
    "revision": 1,
    "operation": {
      "type": "insert",
      "position": 58,
      "value": "spec:\n  containers:\n  - name: app\n    image: nginx\n"
    }
  }
  ```
- **Outgoing Broadcast (Server -> Clients):**
  ```json
  {
    "document_id": "e0a4f6cf-71d3-4927-b9c1-508b5f3dd2f7",
    "revision": 2,
    "transformed_operation": {
      "type": "insert",
      "position": 58,
      "value": "spec:\n  containers:\n  - name: app\n    image: nginx\n"
    },
    "content_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
  ```

---

### 3. Commit & Validation Pipeline
Replays document operations to build the manifest and validates it using `kubectl apply --dry-run=client`.

- **URL:** `POST /api/v1/documents/{id}/commit`
- **Response (200 OK - Valid Manifest):**
  ```json
  {
    "status": "validated",
    "validation_output": "pod/payments created (dry run)"
  }
  ```
  Pending operations in PostgreSQL are marked with `validation_status = 'validated'`.

- **Response (400 Bad Request - Invalid Manifest):**
  ```json
  {
    "status": "failed",
    "validation_output": "error: error parsing payments.yaml: error converting YAML to JSON: ..."
  }
  ```
  Pending operations in PostgreSQL are marked with `validation_status = 'failed'`, and the `validation_failures_total` counter is incremented.

---

### 4. Rollback via Compensating Operations
Reverts a document to an earlier revision by calculating the compensating delta and appending new inverse operations. **No historical operations are deleted.**

- **URL:** `POST /api/v1/documents/{id}/rollback`
- **Request Body:**
  ```json
  {
    "target_revision": 1
  }
  ```
- **Response (200 OK):**
  ```json
  {
    "document_id": "e0a4f6cf-71d3-4927-b9c1-508b5f3dd2f7",
    "new_revision": 4,
    "target_revision": 1
  }
  ```

---

### 5. Prometheus Metrics Endpoint
Application metrics are exposed in Prometheus text format.

- **URL:** `GET /metrics`
- **Required Metrics:**
  - `edit_latency_ms`: Histogram/Summary of OT transformation and broadcast latency.
  - `transform_conflicts_total`: Counter of operations requiring operational transformation.
  - `validation_failures_total`: Counter of failed manifest validations.
  - `snapshot_count`: Gauge of total snapshots stored in PostgreSQL.

---

## Local Development & Testing

Compile and run tests with Maven:
```bash
mvn clean test
```

Start the application locally:
```bash
mvn spring-boot:run
```
