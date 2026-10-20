# Open Questions

This file lists only decisions that the maintainer has to make. Each one has a recommendation. Everything else in the design uses the stated default.

## Decided

| # | Decision | Reason |
|---|---|---|
| Q2 | Maven | Same as Domain Driven Kit, so build conventions and CI patterns carry over |
| Q3 | Java 21 baseline, CI on 21 and 25; Spring Boot 4.1 | Same support matrix as Domain Driven Kit |
| Q4 | Apache-2.0 for code; CC BY 4.0 for the fictional corpus | Permissive; rules out AGPL components such as `pg_search` in the default stack |
| Q11 | `asOf` filters the current version by its validity window; it never selects an older version ([ADR-0005](../adr/0005-as-of-filters-the-current-version.md)) | Historical selection needs rules for versions, labels at the time, retention and an evaluation oracle; a mistake there would be a leak |
| Q6 | Chat model: any OpenAI-compatible endpoint, optional; Ollama is the documented example | Generation stays out of the default quickstart and out of CI. CI tests the deterministic parts (context, validation, refusal) with a scripted model; answer metrics with a real model are local runs that name the model |
| Q8 | Audit is written synchronously and fails closed | Simple, and the correct behaviour at demo scale: an action that cannot be audited does not happen. Revisit with an outbox only if load tests show write pressure |
| Q5 | `BAAI/bge-small-en-v1.5` (MIT, 384 dimensions) through fastembed on CPU | Permissive license, no PyTorch in the image, weights small enough to bake in |
| Q5b | Reranker: `Xenova/ms-marco-MiniLM-L-12-v2` (Apache-2.0, about 120 MB), reranking the top 20 fused candidates | Chosen on the `dev` split only, by reranking the hybrid top 50 of 22 answerable cases on a laptop CPU. MRR@10: no reranking 0.81, MiniLM-L-6 0.89 (0.24 s per query), MiniLM-L-12 0.92 (0.47 s), `bge-reranker-base` 0.96 (1.9 s, 1 GB), both `jina-reranker-v1` models below 0.77. The larger model's lead is within the noise of 22 cases and costs four times the latency. Reranking the top 10 gave the same dev score as the top 50; 20 leaves a margin at about 0.19 s |
| Q1 | Project name `grounded-access` | Kept; the repository is public under that name |
| Q7 | Identity: demo JWT for v0.1, with the trust boundary documented | OIDC is the first item of any real pilot; see the known limitations |
| Q9 | No runtime dependency on Domain Driven Kit; conventions only | Architecture rules are plain ArchUnit tests in this repository |
| Q10 | No demo UI in v0.1 | `./scripts/demo` and the Grafana dashboard cover the demo |
| Q13 | An index is identified by a persisted index profile (model name and exact revision, dimensions, query and passage prefix, embedding context, chunker release), separate from a document's version and from a query's plan; checked at startup and on every model response ([ADR-0007](../adr/0007-what-identifies-an-index.md)) | Two indexes made differently put vectors in different spaces, and search would return wrong neighbours without an error. Building a new index next to the live one and switching to it is a later decision |

## Open for v0.2

| # | Question | Recommendation | Impact if changed |
|---|---|---|---|
| Q12 | A second retrieval backend (for example Elasticsearch) as a comparison? | Not before the PostgreSQL baseline is measured at a larger scale. First write a backend capability contract: authorization and scope predicates pushed into the query, exact-vector baseline, stable candidate ids, model revision, version and deletion visibility, and refusal to serve a strategy it cannot filter safely. A backend that cannot enforce the predicate must fail to start rather than fall back to filtering in the application. Near-real-time indexing makes revocation consistency the hard part. | A new ADR, an evaluation dimension, operational scope |
| Q14 | How are very long sentences and code blocks handled? | Today a chunk can exceed the model service's input limit and the document then fails to ingest. Either split such text at a hard limit with stable offsets, or reject it with a clear error when the job is submitted. A chunker change needs a dev-split decision and a test-split measurement. | Chunker version, ingestion errors |
