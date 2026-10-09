# Known limitations of v0.1

Grounded Access v0.1 is a reference system: it shows one way to put authorization into retrieval and to judge retrieval changes by measurement. It is not a product and has not been hardened for production. This page lists what it does not do, what it does badly, and what was measured to be wrong. Each item points to where the evidence is.

## Do not use it as it is for

- **Real identities.** Tokens are signed with a demo key that is published in this repository so that anyone can mint a token for the local stack. There is no identity provider integration, no token revocation and no key rotation.
- **Real data.** The corpus is fictional. Nothing has been tested on a corpus larger than 35 documents.
- **Unreviewed answers.** Generated answers are wrong often enough to matter. See below.

## Authorization

- The policy is one fixed decision table: tenant, clearance level, one department, a set of projects. There are no roles, groups, per-user grants, deny rules or delegated administration, and no policy language.
- The predicate is enforced by the application. Anyone with the application's database credentials reads everything; PostgreSQL row-level security is not used as a second layer (threat model, R8).
- One administrative role per tenant: whoever may ingest may also change a document's labels (R2).
- The security gate proves the absence of leaks for five demo principals and this corpus. It is a regression gate, not a proof (R10). The decision table is also tested with generated attribute combinations against a reference implementation.
- Response times are not equalized between a hidden document and a missing one (R1).

## Retrieval

- **Small-scale only.** Vector search is exact and linear in the number of passages the caller may read. There is no approximate index, because filtered recall of an approximate index has to be measured first (ADR-0002).
- **A passage is indexed with its heading path and nothing else from its document.** This is new after v0.1. On the test split it shows no detectable gain, and the case that motivated it is still missed by every strategy, because the phrase the question uses is in the document's introduction and not in a heading. Adding a document title to every chunk also lifts unrelated passages of a document whose title matches the question ([analysis](../evaluation/heading-context-analysis.md)).
- **Plain hybrid search does not beat dense search on this dataset.** On paraphrased questions the keyword channel returns confident wrong passages and fusion demotes correct results (cases 1 and 6).
- **Reranking helps ranking, not recall,** and costs about eleven times the request latency. It still ranks a labelled look-alike above the evidence in 8 of 29 cases.
- English only: the full-text configuration, the embedding model and the reranker are English.
- Only Markdown and plain text are ingested. There is no PDF, HTML or office-document parsing, no tables, no images.
- With 70 answerable test cases the confidence intervals are wide. No difference in Recall@10 between strategies is detectable.

## Answers

- **Generated answers are not reliable.** In the one published run (`llama3:8b`), 10 of 24 questions that should have been refused were answered from a look-alike passage, and 2 of 5 instructions planted in readable documents changed the answer ([analysis](../evaluation/m3-answers-analysis.md)).
- Citation validation proves that a statement points at evidence the caller may read. It does not check that the evidence supports the statement.
- A readable document can steer the answer. Detection is a keyword label; nothing is blocked (R3).
- Answer quality is measured on local runs with a named model. CI has no chat model and tests only the deterministic parts.
- No conversation: every question is answered on its own, without history.

## Operations

- **Reranking capacity is about one query per second on a CPU.** Beyond that, queries are answered in the fused order and marked `rerank_unavailable`; a request can still take about 4 s ([load smoke report](../../benchmarks/reports/m4-load-smoke/report.md)).
- The embedding queue is not limited, and there is no rate limiting per caller (R7).
- A timeout ends the wait, not the work: the model service keeps computing a request its caller has given up on.
- The model service is unauthenticated inside the deployment network (R4).
- Audit rows are ordinary table rows without tamper evidence (R6).
- Logs are checked for sensitive text on the failure paths the tests exercise, not on every line a dependency could write (R5).
- One deployment shape: Docker Compose on one machine. No Kubernetes manifests, no high availability, no backup procedure, no schema downgrade path. Pre-release versions change the schema and the dataset without a migration path.
- The load numbers come from one laptop and one CI runner. They are not performance claims.

## Supply chain

- CI scans both images for high and critical vulnerabilities that have a fix, scans the history for secrets, checks dependency licenses and publishes an SBOM per image. It does not verify model weights: the embedding model and the reranker are downloaded at image build time by name. The revision that was downloaded is recorded in every response and execution record, but it is not pinned in this repository, so a rebuild can pick up a newer revision.
- Base images are referenced by tag, not by digest, so two builds of the same commit can differ.
- Two vulnerabilities in a base-image binary that is never executed are accepted and listed with the reason in `ops/supply-chain/trivyignore.yaml`.
- Images are not signed and not published to a registry; they are built locally from source.
