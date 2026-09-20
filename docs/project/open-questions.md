# Open Questions

This file lists only decisions that the maintainer has to make. Each one has a recommendation. Everything else in the design uses the stated default.

## Decided

| # | Decision | Reason |
|---|---|---|
| Q2 | Maven | Same as Domain Driven Kit, so build conventions and CI patterns carry over |
| Q3 | Java 21 baseline, CI on 21 and 25; Spring Boot 4.1 | Same support matrix as Domain Driven Kit |
| Q4 | Apache-2.0 for code; CC BY 4.0 for the fictional corpus | Permissive; rules out AGPL components such as `pg_search` in the default stack |
| Q5 | `BAAI/bge-small-en-v1.5` (MIT, 384 dimensions) through fastembed on CPU; reranker still open | Permissive license, no PyTorch in the image, weights small enough to bake in |

## Open

| # | Question | Recommendation | Impact if changed | Needed by |
|---|---|---|---|---|
| Q1 | Project name `grounded-access`? | Keep it provisionally. Check GitHub, Maven Central and PyPI names before making the repo public. | Package names, docs | Before public repo |
| Q5b | Which cross-encoder reranker? | A base-size model with a permissive license and acceptable training-data terms, latency measured on a laptop before it enters the benchmark. | Image size, latency, eval numbers | M3.1 |
| Q11 | What does `asOf` mean: selecting a historical document version, or only filtering the current version by its validity window? | Decide before any case depends on time. Historical selection needs rules for several versions on one day, for versions the principal could not see at the time, for whether labels are read at query time or as they were then, and an evaluation oracle. Write it as an ADR, then change the schema and API together. | The retrieval join, the case schema, the evaluation oracle | Before M2.3 |
| Q12 | A second retrieval backend (for example Elasticsearch) as a comparison? | Not before M2. First write a backend capability contract: authorization and scope predicates pushed into the query, exact-vector baseline, stable candidate ids, model revision, version and deletion visibility, and refusal to serve a strategy it cannot filter safely. A backend that cannot enforce the predicate must fail to start rather than fall back to filtering in the application. Near-real-time indexing makes revocation consistency the hard part. | A new ADR, an evaluation dimension, operational scope | After M2 |
| Q6 | Local chat model path? | Any OpenAI-compatible endpoint; document Ollama as the example. Keep generation optional. | Only the answering docs and tests | M3.4 |
| Q7 | Identity: demo JWT or OIDC? | Demo JWT for v0.1, with the trust boundary documented. OIDC after v0.1. | Threat model scope | M0.6 |
| Q8 | Audit on the query path: synchronous fail-closed, or asynchronous outbox? | Synchronous fail-closed for v0.1, because it is simple and the correct choice at demo scale. Revisit if load tests show write pressure. | Latency, failure semantics | M2.5 |
| Q9 | Depend on Domain Driven Kit at all? | No runtime dependency in v0.1. Reuse only conventions. Reconsider ArchGuard in test scope after M1. | Architecture-test tooling | After M1 |
| Q10 | Thin demo UI in v0.1? | No. Use a CLI demo script plus trace screenshots. Add a UI only if the demo video needs one. | Scope of M4 | M4.7 |
