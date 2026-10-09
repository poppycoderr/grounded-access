# Changelog

Notable changes per release. Versions before `v0.1.0` are pre-releases: APIs, the schema and the evaluation dataset may change without a migration path.

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
