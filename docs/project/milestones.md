# Milestones (v0.1)

**Time budget assumption:** one maintainer working 10–15 hours a week. One "day" below means about 6 focused hours. Calendar estimates assume about 2 days of work per week. Revisit the plan after M0, once there is a measured pace.

**Rules for every milestone:**

- It ends with something that can be shown publicly: a tagged pre-release, a short changelog entry and, where there are numbers, a report.
- Tenant isolation exists from M0. Retrieval code is never written without the predicate hook.
- Each issue takes 0.5–2 days and states its user value, scope, non-goals, schema or contract changes, tests, telemetry, acceptance criteria and doc updates.

---

## M0 — Walking skeleton (≈ 6 days, 3 weeks)

**Exit:** on a clean machine, one command starts the stack, one command ingests 10 documents for 2 tenants, and one command runs 15 eval cases. The resulting report shows sparse and dense results and **zero cross-tenant candidates**. CI runs all of this.

| # | Issue | Size |
|---|---|---|
| 0.1 | Repository basics: license, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, issue templates | 0.5 |
| 0.2 | Control-plane project, Java toolchain, formatting/lint, ArchUnit test scaffold, CI build | 1 |
| 0.3 | Compose with PostgreSQL and pgvector; migrations for `tenant`, `document`, `document_version`, `chunk`; Testcontainers integration test | 1 |
| 0.4 | Model service with `/v1/embed` only, embedding model baked into the image, contract in `packages/contracts`, contract tests | 1 |
| 0.5 | Minimal manifest ingestion (synchronous, Markdown only, heading chunker) and a `PolicyCompiler` returning a tenant-only predicate | 1 |
| 0.6 | `POST /retrieval/search` with `sparse-only` and `dense-only`, demo JWT verification, `scripts/mint-token` | 1 |
| 0.7 | `ga-eval` skeleton: case schema v1 validation, span→chunk mapping, Recall@k, security gate; 10 documents and 15 cases; CI smoke job | 1.5 |

## M1 — Retrieval baseline (≈ 11 days, 5–6 weeks)

Split into two publishable batches. **M1a proves the dataset can separate strategies; M1b adds hybrid.** Doing hybrid first would tune ranking against a dataset that cannot show a difference.

**M1a exit:** a `test`-split report for `sparse-only`, `dense-only` and the `bm25-reference` row, with paired confidence intervals and failure cases sorted by tag, on a dataset large enough that a one-case change no longer moves a metric by several points.

**M1b exit:** `hybrid-rrf` added to the same report, with a paired comparison against the best single channel and an honest verdict, including "no detectable difference" if that is the result. Published as `v0.1.0-alpha.1`.

| # | Issue | Batch | Size |
|---|---|---|---|
| 1.1 | Dataset growth to ≥ 60 cases: paraphrases, abbreviations, numbers, multi-section evidence, same-tenant hard negatives, no-answer; `DATASET_CARD.md` | M1a | 2 |
| 1.2 | Validator: lexical-overlap bands, per-tag balance, dev/test isolation enforced in the runner | M1a | 1 |
| 1.3 | `hard_negative_documents` scored: false-positive rate and rank of the tempting wrong document | M1a | 0.5 |
| 1.4 | Eval metrics: nDCG@10, bootstrap confidence intervals, paired comparison between configurations | M1a | 1 |
| 1.5 | `bm25-reference` row: offline Python BM25 over the same authorized chunks | M1a | 1 |
| 1.6 | Concurrent-ingestion safety: lock and re-check inside the write transaction, same-key concurrency test | M1a | 1 |
| 1.7 | Asynchronous ingestion jobs in one contract change: `202 + jobId`, `GET /ingestion-jobs/{id}`, `SKIP LOCKED` worker, bounded retry, `failed` state, `ga-eval load` polls | M1b | 2 |
| 1.8 | Disable and delete: status takes effect on the next query, background cleanup of old versions | M1b | 1 |
| 1.9 | Chunker v1: overlap, plain-text rules, chunker version recorded in eval results | M1b | 1 |
| 1.10 | RRF fusion, overlap dedupe, `RetrievalPlan` serialization and hash, debug fields behind the `debug` scope | M1b | 1.5 |

Already delivered in M0, so not repeated here: the atomic `active_version_id` flip, content-hash idempotency, the OR-lexeme FTS query builder, and deterministic tie-breaking.

Deferred on purpose: the precomputed-embeddings cache. CI currently embeds the demo corpus in well under a minute, so the cache would add a moving part without buying time. Revisit if the corpus grows past a few thousand chunks.

## M2 — Authorization (≈ 10 days, 4–5 weeks)

**Exit:** the full decision table is enforced in both channels. The property-based tests and the eval security gate pass with zero unauthorized candidates on ≥ 25 authorization-negative cases. The threat model is published.

| # | Issue | Size |
|---|---|---|
| 2.1 | Label schema on `document_version` (classification, departments, projects) and scope fields (regions, validity); manifest support | 1 |
| 2.2 | Full `PolicyCompiler` implementing the decision table; unit tests per row; `policy_version` | 1 |
| 2.3 | Scope filters with `asOf` and `region` request parameters and server defaults, after the time-semantics ADR (Q11) decides whether `asOf` selects a historical version or only filters the current one | 1 |
| 2.4 | jqwik property tests against the in-memory reference evaluator; architecture test that only `AuthorizedChunkQuery` touches `chunk` | 1 |
| 2.5 | Audit events (schema v1, synchronous, fail closed) and `query_execution` records | 1 |
| 2.5b | Minimal trace metadata alongside the audit record: trace id, policy version, model revision, degraded reasons, plus a test that no forbidden attribute is emitted | 1 |
| 2.5c | Failure tests for authorization changes: audit write failure fails the query closed; a label change or delete takes effect on the next query | 0.5 |
| 2.6 | Existence-leakage behaviour: 404 for invisible documents, identical `no_answer`, no filtered counts; tests | 0.5 |
| 2.7 | Security gate at version and chunk granularity: labels per version, authorization failures reported separately from scope failures, ≥ 25 authorization negatives | 1.5 |
| 2.8 | Threat model: assets, actors, trust boundaries, abuse cases (including a low-privilege author poisoning documents), residual risks | 1 |

## M3 — Reranking and answers (≈ 7 days, 3–4 weeks)

**Exit:** `hybrid-rrf-rerank` appears in the report with its quality delta and p50/p95 latency cost. `/query` returns cited answers or abstentions. Every citation resolves to a chunk that was in the prompt.

| # | Issue | Size |
|---|---|---|
| 3.1 | `/v1/rerank` in the model service, reranker baked into the image, contract tests | 1 |
| 3.2 | Rerank stage with timeout, fallback to RRF order and a `degraded` flag; eval marks degraded runs as invalid | 1 |
| 3.3 | Context builder: token budget, dedupe, `S1..Sn` IDs, version and validity shown with each evidence item | 1 |
| 3.4 | Chat client for an OpenAI-compatible local endpoint; capability declaration; disabled when unset (`evidence_only`) | 1 |
| 3.5 | Structured answer schema (statements with citations), citation validation and mapping, abstention rules | 1.5 |
| 3.6 | Prompt-injection corpus documents, evidence-as-data prompt, heuristic risk tag with its limits documented | 0.5 |
| 3.7 | Answer metrics (citation validity, abstention precision/recall, fact recall) | 1 |

## M4 — Operations and release (≈ 6 days, 3 weeks)

**Exit:** `v0.1.0` is tagged. A clean-machine quickstart is verified in CI, a single trace ID explains a query end to end, and the benchmark report and known limitations are published.

| # | Issue | Size |
|---|---|---|
| 4.1 | OpenTelemetry spans per the overview, the redaction allow-list and a test that no forbidden attribute is emitted | 1 |
| 4.2 | Structured JSON logs with trace IDs; the `observability` compose profile and one dashboard | 1 |
| 4.3 | Failure tests (model service down, chat timeout, audit failure) and a load smoke test (concurrent queries, p95, connection pool) | 1 |
| 4.4 | README commands executed in CI; link checker | 0.5 |
| 4.5 | Full benchmark run, `benchmarks/reports/v0.1.md`, 10 annotated failure cases | 1 |
| 4.6 | Release checklist: SBOM, dependency and secret scanning, license check, release notes, known limitations | 1 |
| 4.7 | Demo script (identity switch, abstention, trace, benchmark) | 0.5 |

---

**Total:** about 41 days of work, or 19–22 calendar weeks at the assumed pace. The demo video and articles come after v0.1 and are not on the critical path. The M1 report can be published early as a first article draft.
