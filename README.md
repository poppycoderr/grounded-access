# Grounded Access

> Permission-aware, evaluation-driven retrieval for enterprise knowledge.

**Status: design phase. Nothing here runs yet.** This README describes the target for v0.1. It is not a list of finished features.

Grounded Access is an open-source reference system for retrieval-augmented generation (RAG) over access-controlled enterprise documents. It sets out to show three things with code, tests and published numbers:

1. **Authorization is part of retrieval.** Tenant isolation and attribute-based access rules are compiled into the same SQL that retrieves candidates. Unauthorized rows never leave the database, so the ranker, the prompt, the logs and the traces never see them.
2. **Retrieval quality is measured.** A versioned, fictional corpus with human-checked ground truth compares four retrieval configurations. The comparison reports confidence intervals, and every result comes from a run anyone can repeat.
3. **Every answer can be traced.** Each answer cites the evidence it used. Each citation is checked against the context the model actually received, and one trace covers every stage of a query.

## Why this exists

Most RAG demos skip the questions an enterprise deployment has to answer:

- Who is allowed to retrieve which content, and is that enforced *before* ranking rather than after?
- Is hybrid search actually better than plain keyword search on this corpus, and by how much, with what uncertainty?
- When an answer is wrong, which stage caused it?

Grounded Access tries to answer these questions for a small, fully fictional company (Northstar Cloud). It does not claim to be a production product.

## v0.1 scope

| In scope | Out of scope for v0.1 |
|---|---|
| Markdown and plain-text ingestion from a manifest | PDF, HTML, OCR, file-upload API |
| Tenant, department, project and clearance authorization | Groups, explicit deny rules, external policy engines |
| Validity dates and document status as *scope* filters | Region-based authorization |
| PostgreSQL full-text search and pgvector exact search, fused with RRF | Approximate indexes (HNSW) tuning, BM25 extensions |
| Local embedding and cross-encoder reranking (CPU) | Cloud model providers |
| Retrieval, security, citation-validity and abstention metrics | LLM-as-judge answer quality |
| Optional local answer generation | Streaming responses, UI beyond a thin demo |
| OpenTelemetry traces and structured logs | Kubernetes, Helm, multi-cloud |

Non-goals for the whole first phase: knowledge graphs, GraphRAG, autonomous agents, multimodal input, fine-tuning, a no-code builder, "support every vector database".

## Target quickstart (not yet implemented)

```bash
docker compose up -d                  # PostgreSQL + control plane + model service
./scripts/load-demo                   # ingest the fictional Northstar corpus
./scripts/demo-queries                # same questions, different identities
./scripts/benchmark                   # four retrieval configurations, JSON + Markdown report
```

No API key is required. Generation is optional and uses a local OpenAI-compatible server if one is configured.

## Architecture at a glance

- **Control plane (Java, Spring Boot):** identity, authorization, ingestion, retrieval, fusion, context building, citation validation, API.
- **Model service (Python, FastAPI):** embeddings and cross-encoder reranking. It holds no identities and has no database access. It only sees candidates the control plane has already authorized.
- **Evaluation (Python CLI):** runs versioned query sets against the public API as different demo identities and writes machine-readable results.
- **PostgreSQL + pgvector:** documents, chunks, full-text and vector search, ingestion jobs, audit events.

See [docs/architecture/overview.md](docs/architecture/overview.md).

## Documentation

- [Architecture overview](docs/architecture/overview.md)
- [Authorization model](docs/architecture/authorization.md)
- [Evaluation strategy](docs/evaluation/strategy.md)
- [Architecture decision records](docs/adr/)
- [Milestones](docs/project/milestones.md)
- [Open questions](docs/project/open-questions.md)

## License

To be decided (see open questions). The demo corpus is fictional and will be licensed separately.
