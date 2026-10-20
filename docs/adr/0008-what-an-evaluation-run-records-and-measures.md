# ADR-0008: What an evaluation run records, and what its ranking metric measures

- Status: Accepted
- Date: 2026-10-20

## Context

Two findings of the 2026-10-15 review (R8, R9) concern the evaluation itself.

**nDCG@10 is not well defined when a case has several evidence spans.** Gain is counted per result that covers a span not covered before, but the ideal ranking assumes one result per span. One chunk at rank 1 that covers both spans of a case scores 0.613; two chunks at ranks 1 and 2 that cover the same two spans score 1.0. A chunking that keeps related evidence together is therefore scored as worse, and the ideal value may not be reachable in a given index. The published reports have not hit this case, but a chunking experiment would.

**A run does not say what it measured.** `run.json` records the dataset digest, the plans, the chunker versions and the evaluator's CPU, but not:

- the server: the git SHA in `run.json` is the evaluator's checkout, which is the server's commit only when both come from the same checkout, as in CI;
- the index profile (ADR-0007) or the reranker revision;
- the server's hardware, a warmup (there is none), or what each attempt of a retried request returned.

Nothing checks that the server, the index or the corpus stayed the same from the first request to the last. CI gates security and scope but has no quality check, and the strategy document now says so. The review asks for a quality check only where results are deterministic, and for no threshold that 70 test cases cannot support.

## Decision

### Every run carries a manifest

`run.json` gets a `manifest` object with its own version (`manifest_version: 1`). It holds:

| Part | Content | Source |
|---|---|---|
| Dataset | version, digest of cases, labels, principals, manifests and corpus files (as today) | evaluator |
| Corpus as served | digest over every chunk the evaluation principals can list: document key, version, chunker version, offsets | evaluator, from `GET /retrieval/chunks` |
| Server | build commit (or `unknown`), application version, index profile with its id, reranker name and revision, retrieval switches that are not part of a plan, CPU model and processor count | new `GET /api/v1/system` |
| Evaluator | its git SHA and whether the checkout had uncommitted changes, `ga-eval` version, CPU model, platform | evaluator |
| Run | strategies, `k`, splits, attempt limit, warmup requests, metrics version | evaluator |

- **`GET /api/v1/system`** describes the deployment, not a tenant: no document, label or principal data. It needs an authenticated token with the `debug` scope, which the evaluation tokens carry. The build commit comes from a build argument (`GA_BUILD_COMMIT`) that CI sets; a local image reports `unknown`, and the evaluator never fills it in with its own SHA.
- **Warmup is recorded, not added.** No request is sent before measuring, so the manifest says `warmup_requests: 0` and latency figures include cold starts. Adding a warmup is a separate change that would only affect latency.
- **Every attempt is recorded.** A case record lists each attempt with its degraded reasons and latency, not only the count of degraded attempts.
- **`ga-eval answers`** writes the same manifest plus the chat model and prompt version it already records.

### A run whose system changed is invalid

The evaluator reads `GET /api/v1/system` and the corpus digest before the first request and again after the last. If the server commit, the index profile, the reranker revision or the corpus digest differs, the run is written with `status: invalid` and the reason `drift`, like a run invalidated by repeated degraded answers. Its results stay on disk and cannot be compared.

### What a comparison requires

`ga-eval compare` keeps its current checks (dataset, digest, metrics version, `k`, the same cases, valid runs) and adds:

- both runs need a manifest, or are named legacy as today; a missing field is never assumed;
- the output lists every manifest field that differs between the two runs, so an experiment states what it changed instead of the reader inferring it;
- latency is compared only when both runs name the same server CPU. Otherwise the latency section says why it is missing. Quality metrics are still compared, but the near-tie note of section 6 of the strategy applies.

### The ranking metric is evidence coverage, not nDCG

The dataset labels evidence spans, not chunks, so that labels survive chunking changes. The ranking metric uses the same unit.

**Evidence coverage at k** (`EC@k`) for one case with spans `S`:

```text
EC@k = (1 / |S|) · Σ over s in S of  1 / log2(1 + r(s))   if r(s) ≤ k
                                     0                    otherwise

r(s) = rank of the first result that covers span s
```

- **Coverage.** A result covers a span when it is from the same document version and their character ranges overlap, the same rule as Recall@k.
- **First hit only.** A second result covering an already-covered span adds nothing, so duplicate or overlapping chunks are not rewarded.
- **Weighting.** Every span weighs the same, and the score is normalized by the number of spans, not by an ideal ranking. The value is between 0 and 1. It is 1 only when the first result covers every span.
- **Maximum depends on the case.** Spans in two documents cannot both be covered at rank 1, so a case's best possible value can be below 1. EC is compared between runs over the same cases, never read as a share of a perfect score.

Worked examples (k = 10):

| Case | Results | EC@10 | nDCG@10 (metrics v1) |
|---|---|---|---|
| two spans, one chunk at rank 1 covers both | rank 1 | (1 + 1) / 2 = **1.000** | 0.613 |
| two spans, covered by ranks 1 and 2 | ranks 1, 2 | (1 + 0.631) / 2 = **0.815** | 1.000 |
| one span, first covered at rank 3, also covered at rank 4 | ranks 3, 4 | 1 / log2 4 = **0.500** | 0.500 |
| two spans of different documents, ranks 2 and 12 | rank 2 only within k | (0.631 + 0) / 2 = **0.315** | 0.387 |
| one span of version 2, results from version 1 only | none | **0** | 0 |

**Metrics version 2.**
- It reports Recall@5, Recall@10, MRR@10 and EC@10. Recall and MRR are unchanged.
- nDCG@10 is no longer computed. A chunk-level nDCG would need an ideal ranking built from a gold mapping of spans to the chunks of each index, which the dataset does not have.

**Old reports are not rewritten.**
- Reports made with metrics version 1 stay as they are and are labelled v1. The README keeps its v1 numbers until a v2 run is published, and then shows EC@10 instead of nDCG@10.
- `ga-eval compare` already refuses to compare two metrics versions.
- An EC@10 value for an old configuration comes only from a new run.

### Quality checks in CI: rankings that must not change by accident

- **Sparse and BM25.** Keyword and BM25 rankings are bit-identical across machines (strategy, section 6). A committed file holds the expected top-10 of `sparse-only` and `bm25-reference` for every case: document, version, offsets and rank. CI runs both strategies and fails on any difference.
- **Changing that file.** A pull request that changes keyword ranking updates the file in the same diff, together with the evaluation evidence AGENTS.md already requires. Nothing updates it automatically.
- **Dense and rerank.** These can reorder near-ties between CPUs, so they have no ranking check.
- **Model quality.** Model quality keeps no automated threshold. A change is compared with `ga-eval compare` on the CI runner and reviewed. A tolerance would have to be declared before the run and backed by repeated runs in one frozen environment, and 70 test cases cannot yet support one.

## Consequences

- A published run names the server, index, models and hardware it measured, and a run whose system changed midway cannot be published as a result.
- `ga-eval compare` shows the experiment's variables explicitly.
- The ranking metric no longer penalizes a chunking that keeps evidence together. Its numbers are not comparable with nDCG@10, which the metrics version enforces.
- CI catches an unintended change in keyword ranking. It does not catch a quality regression in dense or rerank retrieval, and the strategy document keeps saying so.
- New: `GET /api/v1/system` (debug scope), the `GA_BUILD_COMMIT` build argument, `manifest` in `run.json`, an attempts list per case, `EC@10`, a committed ranking file for the deterministic strategies.
- Tests to add: each worked example above plus the overlapping-chunk and multi-version cases, drift of each manifest field, comparison output listing differing fields, latency withheld for different CPUs, the ranking check failing on a one-rank swap, and the system endpoint refusing a token without the `debug` scope and revealing no tenant data.
