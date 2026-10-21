# ADR-0010: Deadlines and admission for model calls

- Status: Accepted
- Date: 2026-10-21

## Context

Model inference is the scarce resource. The cross-encoder scores about one query per second on a CPU, and the embedding model queues under 32 clients (load smoke report). The protection today has three gaps (review, section 5.3):

- **Per-process limit.** The rerank limit is a semaphore inside each control-plane process. Two replicas send twice the load, and the model service itself admits everything.
- **No shared budget.** A query waits up to the rerank timeout for a slot, then gets a full timeout for the call. Nothing bounds the request as a whole.
- **Abandoned work continues.** A call the control plane gave up on keeps running in the model service. So do calls that waited in its thread pool past their caller's patience. Interactive queries and bulk ingestion share one queue, so a large ingestion job slows every search.

## Decision

### The model service admits work, by lane

The model service has three **lanes**:

| Lane | Requests | Concurrent | Waiting at most | Default wait |
|---|---|---|---|---|
| `query` | `/v1/embed` with `input_type: query` | 8 | 16 | 2 s |
| `passage` | `/v1/embed` with `input_type: passage` | 1 | 4 | 30 s |
| `rerank` | `/v1/rerank` | 1 | 4 | 3 s |

- **Admission.** A request takes a slot in its lane or waits in the lane's bounded queue. When the queue is full, it is refused at once with `503 BUSY`. Ingestion can therefore never take the slots of interactive queries.
- **Caller's deadline.** A caller may send `X-Deadline-Ms`, the milliseconds it is still willing to wait. A request whose deadline passes while it waits is refused with `503 DEADLINE_EXCEEDED`, and it is not computed. Without the header, the lane's default wait applies.
- **Running work.** A computation that has started is not interrupted.
- **Timing headers.** Every answered request reports `X-Queue-Ms` and `X-Compute-Ms`.
- **Configuration.** Slot counts, queue sizes and default waits are settings (`GA_QUERY_SLOTS`, `GA_QUERY_QUEUE`, … ). The lanes are the service's total capacity, however many control-plane replicas call it.
- **Defaults.** All slots and queues together stay below the 40 threads FastAPI runs synchronous endpoints on, so waiting requests never block the health check. Query embeddings gain from running in parallel: under 16 clients, 2 slots were 60% slower at the median than no limit, and 8 matched it. The measurements are in the load report.

### The control plane gives each search one deadline

- **Deadline.** A search has one deadline, `ga.retrieval.deadline` (5 s by default), counted from the moment it is received.
- **Dense channel.** The query is embedded only if the remaining time still covers the query-embedding timeout (`ga.model-service.query-timeout`, 2 s). The call sends that remaining time as `X-Deadline-Ms`.
- **Reranking.**
  - Reranking waits for its slot only as long as the remaining time minus the rerank timeout. A search that starts reranking can therefore finish it before its deadline.
  - With less time left, it is not reranked and is marked `rerank_unavailable`, as today.
  - The per-process semaphore stays as a first, cheap filter; the model service decides.
- **Bound.** The model calls of a search therefore end before its deadline. Database time comes on top and is bounded separately: each retrieval statement by `ga.retrieval.sql-timeout` (2 s), after which it fails as `503 DATABASE_TIMEOUT`, and the wait for a pooled connection by the pool timeout (3 s, `503 DATABASE_UNAVAILABLE`). A database failure is never reported as a degraded model channel. Writing the audit rows is not bounded this way (review 2026-10-21, D3).
- **Before admission.** Admission happens on the model service's event loop, before a worker thread is taken. Only admitted work runs on a thread, so requests that wait never hold one, and the health check never waits for one. The caller's deadline counts from the moment the request arrived and is capped by the lane's longest wait. Token counting runs after admission, and the deadline is checked again before the model starts. Tokenizing has its own lane (2 running, 8 waiting), because a large batch costs CPU like inference (review 2026-10-21, D4 and N3).
- **Ingestion.** Passage embeddings send their read timeout as the deadline. A refusal (`BUSY`, `DEADLINE_EXCEEDED`) is retried with backoff, as an unavailable model service is today.
- **Generation.** Answer generation keeps its own timeout. Generation is optional and runs against an external chat model.

### What is recorded

- **Spans.** Model spans carry the queue and compute times the model service reported (`model.queue_ms`, `model.compute_ms`). No text and no principal is recorded.
- **Metrics.** `ga.model.queue` (timer, tag `operation`) and `ga.model.refused` (counter, tags `operation` and `reason`).
- **Degraded reasons.** These are unchanged. A refused or late dense call is `dense_unavailable`; a refused, late or skipped rerank is `rerank_unavailable`. The execution record keeps the stage timings that show why.

## Consequences

- **Overload.** Under overload, searches degrade within their deadline instead of queueing without bound. The model service drops work whose caller has gone.
- **Ingestion and queries.** Ingestion and queries no longer share a queue. They still share the CPU: a passage computation slows a concurrent query computation. Separate processes or machines are the next step if measurements show that this matters.
- **Configuration.** Slot counts are per model-service process. Running two model-service replicas doubles capacity, and each needs its own settings.
- **Tests.**
  - Lanes refuse when full and do not compute requests whose deadline passed.
  - A passage flood does not delay query embeddings beyond their lane.
  - A search under a slow or refusing model service returns within its deadline, degraded.
  - Ingestion retries a refusal.
  - A load run that mixes ingestion with searches, before and after, reports latency, refusals and recovery.
