# Changelog

Notable changes per release. Versions before `v0.1.0` are pre-releases. Before `v1.0`, APIs, the schema and the evaluation dataset may still change without a migration path.

## Unreleased

### Retrieval

- **Changed:** each chunk is embedded and keyword-indexed together with its heading path. The chunker version is `markdown/3` / `text/3`; the stored chunks and their offsets are the same as in release 2. An index built with v0.1.0 has to be rebuilt in a fresh database to get the new embeddings; migration V9 re-indexes keyword search in place.
- On the test split the change shows **no detectable difference** in any strategy (for example `hybrid-rrf` MRR@10 +0.04 [−0.01, +0.10]). It was chosen on the `dev` split and is kept as the default because the test split shows no harm. The case that motivated it is still not retrieved ([analysis](./docs/evaluation/heading-context-analysis.md)).
- New configuration: `GA_EMBEDDING_CONTEXT`, `GA_SPARSE_CONTEXT`, `GA_RERANK_CONTEXT`.

### Evaluation

- `ga-eval compare <before> <after>` pairs two runs case by case and lists the cases whose rank changed.
- The BM25 reference row indexes heading paths when the keyword channel does.

## v0.1.0

Milestone M4: operations and release. The first release that is not a pre-release. It is a reference system and has not been hardened for production; read the [known limitations](./docs/project/known-limitations.md) first.

### Observability

- Every request is one OpenTelemetry trace with a span per pipeline stage. Its trace id is the `X-Trace-Id` of the response and the `trace_id` of its audit rows and execution record.
- Spans carry only attributes from an allow-list, enforced where spans are exported: other attributes, span events and status descriptions are dropped, including those added by HTTP instrumentation.
- Logs in the compose stack are JSON with trace ids. Log lines never contain exception messages, which can quote request text; they name the exception class, HTTP status and SQL state.
- `COMPOSE_PROFILES=observability docker compose up -d` starts a collector with Grafana and a dashboard. Without it nothing is exported.

### Behaviour under failure and load

- Failure behaviour is tested over the real HTTP clients against a model service that hangs, fails or answers nonsense.
- `./scripts/load-smoke` sends the evaluation cases concurrently and checks every result against its principal's visible set. CI runs it.
- **Changed:** one rerank call is in flight at a time (`GA_RERANK_MAX_CONCURRENT`, default 1). A query that finds no free slot within the rerank timeout is answered in the fused order and marked `rerank_unavailable`. Before, overlapping rerank calls all timed out and slowed every other query ([report](./benchmarks/reports/m4-load-smoke/report.md)).

### Evaluation

- [v0.1 benchmark report](./benchmarks/reports/v0.1.md) with ten annotated failure cases. The retrieval results are those of alpha.3, measured again on the release code.
- **Changed:** the benchmark sends a degraded request again, up to three times, before it declares the run invalid, and reports how often that happened.
- **Fixed:** loading the demo corpus a second time added versions to documents with a history, and the next benchmark reported them as a security failure. Loading is now repeatable.

### Release engineering

- CI runs the README quick start as printed, checks relative links, scans the history for secrets and both images for fixable high and critical vulnerabilities, checks dependency licenses and publishes an SBOM per image.
- **Security:** Tomcat is raised to 11.0.25 and Jackson to 3.1.7. The versions managed by Spring Boot 4.1.1 have known critical and high vulnerabilities.
- [Known limitations](./docs/project/known-limitations.md) and a [release checklist](./docs/project/release-checklist.md).

### Changed since alpha.3

- New dependency: the Spring Boot OpenTelemetry starter.
- New configuration: `GA_TRACE_SAMPLING`, `GA_OTLP_ENDPOINT`, `GA_OTLP_METRICS_ENABLED`, `GA_RERANK_MAX_CONCURRENT`.
- `./scripts/demo-queries` prints two results per identity in the tenant comparison.
- `./scripts/demo` is a guided tour of the running stack that checks each claim it makes; `--pause` steps through it.

## v0.1.0-alpha.3

Milestone M3: reranking and answers.

### Retrieval

- A fourth strategy, `hybrid-rrf-rerank`, sends the top 20 fused candidates to a cross-encoder (`Xenova/ms-marco-MiniLM-L-12-v2`, chosen on the `dev` split) and orders them by its score. The cross-encoder receives only passages the authorization predicate admitted.
- Reranking has its own timeout. If it fails, the fused order is returned and the response says `rerank_unavailable`.
- Published result ([report](./benchmarks/reports/m3-retrieval/report.md)): reranking beats dense on MRR@10 by +0.09 [+0.04, +0.15] at about ten times the request latency. Plain hybrid still shows no detectable difference from dense.

### Answers

- `POST /api/v1/query` answers with statements that cite authorized evidence, refuses with `no_answer` when the evidence does not answer the question, and returns the evidence alone when no chat model is configured.
- The chat model is optional and can be any OpenAI-compatible endpoint. It sees only what retrieval returned for the principal. Its reply is validated: a statement survives only if every citation names evidence that was in the prompt.
- A refusal carries no evidence, so it is the same whether the answer is hidden or does not exist.

### Evaluation

- Dataset v4: 35 fictional documents and 128 cases, adding documents that carry an instruction aimed at a language model.
- `ga-eval answers` measures abstention, citation of the labelled evidence, fact recall and steered prompt-injection cases. Answer reports are local runs that name their model; CI tests the deterministic parts with a scripted model.
- First answer run with `llama3:8b` ([analysis](./docs/evaluation/m3-answers-analysis.md)): no hidden content leaked and every citation resolved, but 10 of 24 questions that should have been refused were answered from a readable look-alike document, and 2 of 5 planted instructions added a false statement. **Generated answers are not reliable yet.** Citation validation proves that a statement points at authorized evidence, not that the evidence supports it.
- Reports record per-strategy request latency and the CPU they were measured on.

### Changed since alpha.2

- The model-service image now contains the reranker and is about 1 GB.
- `GET /v1/models` of the model service lists two models; `dimensions` and `query_prefix` are optional.
- The evaluation dataset moved to `data/eval/v4`.

### Not in this release

A check that each statement is supported by the passage it cites, OpenTelemetry traces, and failure and load tests. See the [milestones](./docs/project/milestones.md).

## v0.1.0-alpha.2

Milestone M2: authorization. The decision table is enforced in every retrieval path, and the evaluation checks it against hand-written labels.

### Authorization

- Clearance, department and project rules join the tenant rule in one compiled predicate (`policy abac/1`). The predicate text is the same for every principal; a missing or unknown attribute grants the least access.
- Documents carry access labels. A label change creates a version that takes over the existing chunks, so it needs no re-embedding and applies to the next query.
- Region and validity are scope, compiled apart from the predicate. `asOf` filters the current version by its validity window and never selects an older version (ADR-0005).
- `GET /api/v1/documents/{key}` reads an authorized document. Every refusal is the same 404, whether the document is hidden, disabled, deleted, in another tenant or does not exist.

### Audit and tracing

- Searches, chunk listings, document reads, ingestion submissions and status changes write audit events synchronously. If the write fails, the request fails: a search returns nothing and an administrative change is rolled back.
- Each search stores an execution record with its plan, policy version, model revision, degraded reasons and timings. Its owner can read it back.
- Every request has a trace id, taken from a valid `traceparent` header or generated.

### Evaluation

- Dataset v3: 32 fictional documents and 122 cases, including 31 authorization negatives and 13 scope cases.
- The security gate checks every returned chunk for document and version, and compares each principal's full chunk listing with its hand-written visible set. Scope failures are reported apart from security violations.
- Published result ([report](./benchmarks/reports/m2-authorization/report.md)): zero unauthorized results, zero scope failures; dense beats PostgreSQL full-text search on MRR@10, and hybrid shows no detectable difference from dense. The earlier claim that full-text search is measurably below BM25 did not hold on the larger dataset and is withdrawn.
- `run.json` records the chunker versions, the retrieval plans and the CPU model.

### Security documentation

- The [threat model](./docs/security/threat-model.md) lists each control with the check that verifies it, and the risks that are accepted. The largest is that the demo identity setup lets anyone mint any token.

### Changed since alpha.1

- Search responses carry `scope`, `executionId` and `traceId`.
- The evaluation dataset moved from `data/eval/v1` to `data/eval/v3`. Reports on different dataset versions are not comparable.
- The ingestion request accepts `classification`, `allowedDepartments`, `requiredProjects`, `appliesToRegions`, `validFrom` and `validTo`.

### Not in this release

Reranking, answer generation with citations and abstention, and OpenTelemetry traces. See the [milestones](./docs/project/milestones.md).

## v0.1.0-alpha.1

The first tagged pre-release. It covers milestones M0, M1a and M1b: a walking skeleton with tenant isolation, an evaluation baseline, and hybrid retrieval with a published verdict.

### Authorization

- The tenant predicate is compiled once per request and embedded in every retrieval query. `AuthorizedChunkQuery` is the only reader of the chunk table, and an architecture test enforces that.
- Only tenant isolation is enforced. Clearance, department and project rules are milestone M2.
- Demo identities are JWTs signed with a published demo key. They are for local use only.

### Retrieval

- Three strategies: `sparse-only` (PostgreSQL full-text search), `dense-only` (exact pgvector search) and `hybrid-rrf` (reciprocal rank fusion with deduplication of overlapping chunks).
- Every response carries the hash of its retrieval plan. Scores and per-channel ranks are returned to tokens with the `debug` scope only.
- A hybrid query that cannot be embedded is answered from the sparse channel and marked `degraded`.

### Ingestion

- Asynchronous jobs: `202` and polling, a `SKIP LOCKED` worker with leases, bounded retry and resume after the last recorded document.
- Content-hash idempotency, an atomic switch between document versions, and version numbers that are never reused.
- Documents can be disabled and deleted; the change applies to the next query, and a background job removes unreachable versions.
- Markdown and plain-text chunking with sentence-level splitting and overlap inside a section.

### Evaluation

- 70 hand-checked cases over 21 fictional documents, with paraphrases, hard negatives and cross-tenant probes, split into `dev` and `test`.
- Evidence is labelled as a document version plus a quote, so chunking strategies are compared on the same labels.
- Bootstrap confidence intervals, paired comparisons, an offline BM25 reference row, and a security gate that fails CI on any unauthorized result.
- Published result ([report](./benchmarks/reports/m1b-hybrid/report.md), [analysis](./docs/evaluation/m1b-hybrid-analysis.md)): dense beats PostgreSQL full-text search on MRR@10, full-text search is measurably below BM25, and hybrid shows no detectable difference from dense.

### Not in this release

Label-based access rules, audit events, existence-leakage guarantees beyond retrieval, reranking, answer generation with citations, and tracing. See the [milestones](./docs/project/milestones.md).
