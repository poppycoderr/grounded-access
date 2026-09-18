# Open Questions

This file lists only decisions that the maintainer has to make. Each one has a recommendation. Everything else in the design uses the stated default.

## Decided

| # | Decision | Reason |
|---|---|---|
| Q2 | Maven | Same as Domain Driven Kit, so build conventions and CI patterns carry over |
| Q3 | Java 21 baseline, CI on 21 and 25; Spring Boot 4.1 | Same support matrix as Domain Driven Kit |
| Q4 | Apache-2.0 for code; CC BY 4.0 for the fictional corpus | Permissive; rules out AGPL components such as `pg_search` in the default stack |

## Open

| # | Question | Recommendation | Impact if changed | Needed by |
|---|---|---|---|---|
| Q1 | Project name `grounded-access`? | Keep it provisionally. Check GitHub, Maven Central and PyPI names before making the repo public. | Package names, docs | Before public repo |
| Q5 | Which embedding and reranker models? | A small English embedding model (about 384 dimensions) and a base-size cross-encoder. Both must have permissive licenses and acceptable training-data terms, and their CPU latency must be measured on a laptop. | Image size, latency, eval numbers | M0.4 / M3.1 |
| Q6 | Local chat model path? | Any OpenAI-compatible endpoint; document Ollama as the example. Keep generation optional. | Only the answering docs and tests | M3.4 |
| Q7 | Identity: demo JWT or OIDC? | Demo JWT for v0.1, with the trust boundary documented. OIDC after v0.1. | Threat model scope | M0.6 |
| Q8 | Audit on the query path: synchronous fail-closed, or asynchronous outbox? | Synchronous fail-closed for v0.1, because it is simple and the correct choice at demo scale. Revisit if load tests show write pressure. | Latency, failure semantics | M2.5 |
| Q9 | Depend on Domain Driven Kit at all? | No runtime dependency in v0.1. Reuse only conventions. Reconsider ArchGuard in test scope after M1. | Architecture-test tooling | After M1 |
| Q10 | Thin demo UI in v0.1? | No. Use a CLI demo script plus trace screenshots. Add a UI only if the demo video needs one. | Scope of M4 | M4.7 |
