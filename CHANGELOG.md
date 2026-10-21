# Changelog

Notable changes per release. Versions before `v0.1.0` are pre-releases. Before `v1.0`, APIs, the schema and the evaluation dataset may still change without a migration path.

## Unreleased

### Fixed (review 2026-10-21)

- **Text outside the Basic Multilingual Plane could be split into invalid characters.** The model service reports fitting lengths in Unicode code points and the chunker counted Java chars, and its hard cuts could fall between the two halves of a surrogate pair. The chunker now converts the unit and never cuts inside a pair; `ga-eval validate` refuses corpus files outside the plane, because evaluation offsets are code points and API offsets are UTF-16 units.
- **The first search after the model service came back could exceed its deadline.** It established the index profile on its own time, with a passage probe that had no deadline. The profile is now established once, in the background; searches meanwhile answer from the sparse channel (`dense_unavailable`).
- **Two configurations could write two index profiles into one database.** The database is now anchored to the profile of its first written version (migration V13); other profiles are refused when they write and at startup, also from processes that started while the database was empty.
- **Retrieval SQL had no time limit.** Each retrieval statement is cancelled after `GA_RETRIEVAL_SQL_TIMEOUT` (2 s) and fails as `503 DATABASE_TIMEOUT`; a request that gets no pooled connection within `GA_DB_CONNECTION_TIMEOUT_MS` (3 s) fails as `503 DATABASE_UNAVAILABLE`. Neither is reported as a degraded model channel.

### Retrieval

- **Deadlines and admission for model calls** ([ADR-0010](./docs/adr/0010-deadlines-and-admission-for-model-calls.md)). A search has one deadline (`GA_RETRIEVAL_DEADLINE`, 5 s) for its model calls: the query is embedded only if its timeout still fits, and reranking starts only if a whole call still fits. The model service admits work by lane (query embeddings, passage embeddings, reranking), each with bounded slots and a bounded queue; a full lane refuses at once (`503 BUSY`) and a request whose caller's deadline passed while it waited is refused without being computed (`503 DEADLINE_EXCEEDED`). Ingestion no longer shares a queue with interactive queries. New: `X-Deadline-Ms`, `X-Queue-Ms` and `X-Compute-Ms` headers, `ga.model-service.query-timeout`, the `model.queue_ms` and `model.compute_ms` span attributes, and the `ga.model.queue` and `ga.model.refused` metrics.

- **Long inputs are split, never truncated** (chunker release 4, [ADR-0009](./docs/adr/0009-how-long-inputs-are-handled.md)). No chunk is longer than 2,000 characters, and every embedding input fits 384 tokens as both models' tokenizers count them; a longer sentence, code block or space-free run is cut at a line break, else whitespace, with offsets intact. At most 200 characters of the heading path are embedded. The model service refuses an input over the model's 512-token limit instead of embedding its beginning, has a new `POST /v1/tokenize`, and reports when it cut a reranking query (`rerankQueryTruncated` in the execution record). A query too long to embed answers `400 QUERY_TOO_LONG`. Before, a 10,000-character sentence failed its ingestion job at the model service, and any input over 512 tokens was embedded from its first part only. On the demo corpus release 4 produces the same chunks as release 3. The length limits join the index profile (migration V12), so an existing index has to be reloaded into an empty database.

- **Changed:** each chunk is embedded and keyword-indexed together with its heading path. The chunker version is `markdown/3` / `text/3`; the stored chunks and their offsets are the same as in release 2. An index built with v0.1.0 has to be rebuilt in a fresh database to get the new embeddings; migration V9 re-indexes keyword search in place.
- On the test split the change shows **no detectable difference** in any strategy (for example `hybrid-rrf` MRR@10 +0.04 [−0.01, +0.10]). It was chosen on the `dev` split and is kept as the default because the test split shows no harm. The case that motivated it is still not retrieved ([analysis](./docs/evaluation/heading-context-analysis.md)).
- New configuration: `GA_EMBEDDING_CONTEXT`, `GA_SPARSE_CONTEXT`, `GA_RERANK_CONTEXT`.
- **Index profile.** Every version records the profile its chunks and vectors were made with: embedding model and exact revision, dimensions, query and passage prefix, embedding context, chunker release and chunk size (migration V11 derives it for existing versions). Dense search compares a query only with vectors of its own profile. The control plane refuses to start when the stored profile is not the one it would produce, and a model service that changes while running is noticed on its next answer: searches fall back to the sparse channel (`dense_index_mismatch`) and ingestion fails without a retry (`INDEX_PROFILE_MISMATCH`). Before, only the model name was compared ([ADR-0007](./docs/adr/0007-what-identifies-an-index.md)).
- **Model responses are checked.** Embeddings must come from the configured model at a known revision, one finite, non-zero vector of the stated size per text, with the same revision in every batch; rerank scores must be finite, one per passage id sent. A response that is not is never used (`dense_invalid_response`, `rerank_unavailable`, or `MODEL_RESPONSE_INVALID` for ingestion).
- **Pinned model weights.** The embedding model and the reranker are loaded from a pinned Hugging Face commit (`GA_EMBEDDING_REVISION`, `GA_RERANKER_REVISION`); the model-service image no longer picks up whatever revision is newest when it is built. The embed response reports the prefix it applied. A deployment upgraded from v0.1.0 keeps its index if its image downloaded the revisions that are now pinned (the newest ones when this change was made); otherwise the control plane names both profiles and refuses to start until the corpus is reloaded.

### Fixed

- **Label and scope changes could be missed.** Whether a submission changed a document's labels or scope was decided by comparing fingerprints of a delimited encoding, and two different department sets could encode alike. A label change that narrowed access could then be taken as "unchanged". Metadata is now compared field by field, sets as sets; the fingerprint columns are dropped (migration V10, no version changes). Label and region values with control characters are rejected ([ADR-0006](./docs/adr/0006-what-a-document-version-is.md)).
- **A title or source link change was ignored.** It now creates a new version that keeps the existing embeddings. An empty source link is the same as none.
- **A worker that lost its job could still write.** A worker held up until its lease expired, or until another worker took the job over, could still write the document it was working on, for example an older text over a newer one. Each document is now committed in one transaction with the job's progress, after checking that the worker still owns the job, and leases are renewed in the background while a job runs.

### Evaluation

- The README now quotes [`benchmarks/reports/metrics-v2`](./benchmarks/reports/metrics-v2/), the CI run of `b878bb5`: the first published run with EC@10, a manifest and chunker release 4. Every ranked list equals the previous published run's, so Recall and MRR are unchanged; the latency ratio of reranking to dense on that runner is about ten instead of eleven.

- **Run manifest.** `run.json` records what was measured: the server's build commit, index profile, reranker revision, retrieval settings and CPU (from the new `GET /api/v1/system`, debug scope), a digest of the corpus as listed, the evaluator's commit and whether it had uncommitted changes, the attempt limit and `warmup_requests: 0`. Every attempt of a retried request is kept. The server and corpus are read again after the last request, and a run during which any of them changed is invalid (`drift`). `ga-eval answers` records the same ([ADR-0008](./docs/adr/0008-what-an-evaluation-run-records-and-measures.md)).
- **Evidence coverage replaces nDCG@10** (metrics version 2). nDCG's ideal ranking assumed one result per evidence span, so one chunk covering two spans scored 0.613 and two chunks covering them scored 1.0. EC@10 scores each span by the rank of its first hit. Version 1 reports are unchanged; comparing the two versions is refused.
- **`ga-eval compare`** lists every manifest field that differs between the two runs and compares latency only when both were served by the same CPU.
- **Expected keyword rankings.** The top 10 of `sparse-only` and `bm25-reference` for every case are committed in `benchmarks/expected/keyword-rankings.json`; a run that ranks differently exits with code 4. `ga-eval rankings` regenerates the file from a run, on purpose and in the pull request that changes ranking.
- **Fixed:** a degraded response was retried before it was checked, so an unauthorized result in a retried response was not counted. Every response is now checked first; a violation or scope failure in any attempt fails the gate.
- **Fixed:** `ga-eval answers` exited with 0 when there were scope failures. It now exits with 3, as `ga-eval run` does.
- An invalid run (three degraded answers in a row) writes what it measured, marked invalid, before failing.
- New runs record `dataset_digest` and `metrics_version`. `ga-eval compare` refuses runs that cover different cases, record a case twice, use a different `k`, labels or metric version, or failed a gate; `--allow-subset` compares the shared cases and says so.
- `ga-eval compare <before> <after>` pairs two runs case by case and lists the cases whose rank changed.
- The BM25 reference row indexes heading paths when the keyword channel does.

### Documentation

- The package dependency table, the absence of cycles, `answering` staying away from JDBC and the corpus, and the rule that only the authorized query creates authorized rows are now architecture tests; the overview says which rules are tested and that the last one is still not a type guarantee.

- Corrected statements that did not match the code: ADR-0001 to 0004 are accepted, the model-service contract is the generated `.openapi.json`, `answering` receives `RetrievedChunk` rows by convention rather than a restricted type, the architecture tests are described by what they check, the query flow is sequential, and CI gates security and scope but no quality metric.

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
