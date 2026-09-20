<p align="center">
    <img src="./assets/brand/logo.svg" alt="Grounded Access" width="96" />
</p>

<h1 align="center">Grounded Access</h1>

<p align="center">
    <b>Retrieval that applies authorization inside the search query, with a reproducible evaluation behind every retrieval change.</b><br/>
    <b>Today:</b> tenant isolation in SQL, PostgreSQL FTS and exact pgvector search, an end-to-end demo benchmark. <b>Next:</b> hybrid ranking and the full attribute-based decision table.
</p>

<p align="center">
    <a href="https://github.com/poppycoderr/grounded-access/actions/workflows/build.yml"><img src="https://github.com/poppycoderr/grounded-access/actions/workflows/build.yml/badge.svg" alt="Build" /></a>
    <a href="./LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License" /></a>
    <img src="https://img.shields.io/badge/status-M0%20walking%20skeleton-8B5CF6" alt="Status" />
</p>

<p align="center">
    <b>English</b> · <a href="./README.zh-CN.md">简体中文</a> · <a href="./docs/architecture/overview.md">Architecture</a> · <a href="./docs/evaluation/strategy.md">Evaluation</a> · <a href="./benchmarks/reports/m0-walking-skeleton/report.md">Benchmark</a> · <a href="./docs/project/milestones.md">Milestones</a>
</p>

---

## Same question, two identities

Nothing about the request changes except the token. This is real output from `./scripts/demo-queries`:

```text
alice-engineer (tenant northstar) · dense-only · policy tenant-only/1
  1. hr-volunteer-policy › Volunteer Time Off Policy > European Union
     Employees based in the EU receive two paid volunteer days per calendar year.
  2. hr-volunteer-policy › Volunteer Time Off Policy > United States
     Employees based in the US receive one paid volunteer day per calendar year.

mallory-outsider (tenant external) · dense-only · policy tenant-only/1
  1. volunteer-handbook › Community Volunteering Handbook > Volunteer days
     Orbit Labs employees receive three volunteer days per year, which can be taken as half days.
```

The outsider gets no "permission denied", no hit count and no Northstar document title. The tenant condition is part of the SQL that selects candidates, so the Northstar policy is never a row in their result set.

## What works today

| Capability | Today | Planned |
|---|---|---|
| Authorization in the retrieval query | Tenant isolation, compiled once per request and carried by both channels | Clearance, department and project labels (M2) |
| Retrieval | `sparse-only` (PostgreSQL FTS) and `dense-only` (exact pgvector) | RRF hybrid (M1), cross-encoder reranking (M3) |
| Ingestion | Markdown and text, synchronous, content-hash versioning, heading-aware chunks | Job queue with retries (M1), disable and delete cleanup (M1) |
| Evaluation | 15 cases, span-based relevance, Recall@k, MRR, security gate in CI | Larger dataset with hard negatives, BM25 reference row, confidence intervals (M1) |
| Answers | Ranked evidence from `/api/v1/retrieval/search` | `/api/v1/query` with citations and abstention (M3) |
| Operations | Docker Compose, CI on every PR | OpenTelemetry traces, audit events, dashboards (M2–M4) |

Design goals that are **not** yet verified end to end, and the milestone that will verify them: hiding the existence of content a principal may not see (M2–M3), attribute-based access decisions (M2), answers that cite only what the model was shown (M3).

## Quick start

Requirements: Docker, [uv](https://docs.astral.sh/uv/). The first build downloads Maven dependencies and a ~70 MB embedding model, which usually takes a few minutes. No API key, no GPU.

```bash
git clone https://github.com/poppycoderr/grounded-access.git
cd grounded-access

docker compose up -d --build --wait   # PostgreSQL + pgvector, control plane, CPU model service
./scripts/load-demo                   # ingest the fictional Northstar and Orbit Labs corpora
./scripts/demo-queries                # the comparison above
./scripts/benchmark                   # evaluate every strategy and enforce the security gate
```

Search as any demo identity, or mint a token and call the API yourself:

```bash
uv run --project packages/evaluation ga-eval search alice-engineer "How many paid volunteer days do EU employees receive?"

TOKEN=$(uv run scripts/mint-token.py alice-engineer --scope "query debug")
curl -s localhost:8080/api/v1/retrieval/search -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"query":"paid volunteer days","strategy":"dense-only","k":3}'
```

## Why it exists

Most RAG demos retrieve first and filter afterwards. That leaks rows into application memory — and from there into rerankers, prompts and logs — while silently costing recall, because the filter eats the top-k that the index already chose.

<p align="center">
    <img src="./assets/diagrams/ga-authorization.en.svg" alt="Authorization is a retrieval concern, not a post-filter" />
</p>

Today the predicate carries the tenant condition; M2 adds clearance, department and project to the same compiled object. The shape of the solution is the point: whatever the rules are, they belong in the query that selects candidates.

## Retrieval evaluation

[`benchmarks/reports/m0-walking-skeleton/`](./benchmarks/reports/m0-walking-skeleton/) holds the committed run: `run.json` (dataset version, commit, strategies, policy version, platform), `cases.jsonl` (per-case rankings) and the rendered `report.md`. Regenerate it with `./scripts/benchmark --out benchmarks/reports/<name>`.

| Strategy | Answerable cases | Recall@5 | Recall@10 | MRR@10 | Security violations |
|---|---|---|---|---|---|
| `sparse-only` (PostgreSQL FTS) | 13 | 0.923 | 0.923 | 0.705 | **0** |
| `dense-only` (pgvector, exact) | 13 | 1.000 | 1.000 | 0.910 | **0** |

**Read these numbers carefully.** The dataset is 15 cases over 10 fictional documents, so a single case is worth 7.7 points and the two strategies are not separated by anything like a significant margin. Dense retrieval answering everything means the dataset is currently too easy for it, not that the system is good. M1 grows the dataset with hard negatives and paraphrases, adds a BM25 reference row, and reports paired confidence intervals — only then is a hybrid-versus-baseline claim worth making.

What the run does establish: the method is reproducible. Two fresh databases and a GitHub runner produce identical rankings, and the security gate counts zero unauthorized results at tenant level.

Evidence is labelled as a **document version plus a quote**, not a chunk id, so chunking strategies can be compared on the same labels. Read the method in [docs/evaluation/strategy.md](./docs/evaluation/strategy.md).

## Architecture

<p align="center">
    <img src="./assets/diagrams/ga-overview.en.svg" alt="Grounded Access request path" />
</p>

| Component | Stack | Role |
|---|---|---|
| `apps/control-plane` | Java 21 (CI on 21 and 25), Spring Boot 4.1 | Token verification, policy compilation, ingestion, retrieval, API |
| `apps/model-service` | Python 3.12, FastAPI, ONNX Runtime | Embeddings today, reranking in M3; no identities, no database access |
| `packages/contracts` | OpenAPI | The contract between them, with a drift test on both sides |
| `packages/evaluation` | Python 3.12 | `ga-eval`: dataset validation, retrieval metrics, security gate, reports |
| Storage | PostgreSQL 17 + pgvector | Documents, versions, chunks, full-text and vector search |

```text
io.groundedaccess
├── identity        # verify the JWT → Principal (attributes only from the token)
├── authorization   # PolicyCompiler → one parameterized SQL predicate
├── corpus          # normalize · heading-aware chunking · content-hash versioning
├── retrieval       # AuthorizedChunkQuery: the only reader of the chunk table
├── modelclient     # model-service client: batching, timeouts, pinned model
└── api             # REST controllers, scopes, problem responses
```

## How authorization works

A principal is compiled into a predicate with bound parameters, never string concatenation, and both channels embed the same object.

Implemented today (`policy tenant-only/1`):

```sql
c.tenant_id = :auth_tenant_id
```

Planned for M2, added to the same compiled predicate:

```sql
AND v.classification_rank <= :clearance_rank
AND (cardinality(v.allowed_departments) = 0 OR :department = ANY(v.allowed_departments))
AND (cardinality(v.required_projects)  = 0 OR v.required_projects && :projects::text[])
```

Tenant, clearance, department and project are **authorization** and count toward the security gate. Region, validity dates and document status are **scope**: they shape relevance rather than access. Keeping them apart means the security metrics only ever count real access violations. The full decision table is in [docs/architecture/authorization.md](./docs/architecture/authorization.md).

Guards in place today:

- an architecture test — only `AuthorizedChunkQuery` may read the chunk table;
- integration tests on real pgvector — cross-tenant isolation on both channels, invalid and under-scoped tokens, version replacement;
- the evaluation security gate — returned documents are compared against hand-labelled visibility, never against the compiler itself.

Planned with M2: property-based tests over the decision table, a gate that checks version and chunk granularity, and revocation-consistency tests.

## Roadmap

| Milestone | Scope | Status |
|---|---|---|
| **M0** Walking skeleton | Demo identities, Markdown ingestion, sparse and dense retrieval, tenant isolation, eval CLI, CI smoke benchmark | ✅ Done |
| **M1** Retrieval baseline | Harder dataset with hard negatives, BM25 reference, paired confidence intervals, async ingestion jobs, chunker v1, RRF hybrid | ⏳ Next |
| **M2** Authorization | Access labels, full decision table, scope filters, audit events, minimal trace metadata, threat model | Planned |
| **M3** Reranking and answers | Cross-encoder reranking with fallback, context builder, structured citations, abstention | Planned |
| **M4** Operations and release | Traces and dashboards, failure and load tests, v0.1 benchmark report | Planned |

Not in the first phase: knowledge graphs or GraphRAG, autonomous agents, extra vector databases, OCR and multimodal input, fine-tuning, Kubernetes and multi-cloud, a no-code builder. See [docs/project/milestones.md](./docs/project/milestones.md).

## Documentation

- [Architecture overview](./docs/architecture/overview.md) — trust boundaries, data model, ingestion and query flows, failure behaviour
- [Authorization model](./docs/architecture/authorization.md) — invariants, decision table, compiled SQL, what is verified today
- [Evaluation strategy](./docs/evaluation/strategy.md) — case schema, metrics, CI gates, reproducibility rules
- [Architecture decisions](./docs/adr/) — modular monolith, PostgreSQL FTS + pgvector, retrieval-time authorization, the Python model service
- [Milestones](./docs/project/milestones.md) and [open questions](./docs/project/open-questions.md)

## Contributing

Issues and pull requests are welcome, and the most useful ones right now are evaluation cases that break the retrieval baseline, and arguments against the design decisions in the ADRs. Read [CONTRIBUTING.md](./CONTRIBUTING.md) and [AGENTS.md](./AGENTS.md) first — the rules in `AGENTS.md` apply to humans and AI agents alike.

Security reports go through [GitHub security advisories](https://github.com/poppycoderr/grounded-access/security/advisories/new). The demo identity setup is insecure by design; see [SECURITY.md](./SECURITY.md).

## Related projects

- [domain-driven-kit](https://github.com/poppycoderr/domain-driven-kit) — executable DDD for Spring Boot. Grounded Access reuses its engineering conventions (architecture tests, null safety, CI layout) without depending on it.

## License

[Apache-2.0](./LICENSE). The fictional demo corpus in `data/corpus` is published under CC BY 4.0 and describes companies that do not exist.
