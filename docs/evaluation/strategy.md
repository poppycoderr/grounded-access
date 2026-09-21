# Evaluation Strategy

Status: draft for v0.1.

The evaluation work is part of the product. Every retrieval change has to show its effect on this evaluation before it is merged.

## 1. Ownership

- A Python package (`packages/evaluation`) with its own CLI (`ga-eval`) owns evaluation runs. The control plane has no evaluation-run resource.
- The CLI talks to the public API. For each case it mints a token for the case's principal and calls `/retrieval/search` or `/query`. It never reads the database.
- Runs write result files. Nothing is stored in the service.

## 2. Dataset

```text
data/
├── corpus/northstar/            # fictional Markdown/TXT documents
├── manifests/northstar.yaml     # document labels, versions, validity
├── principals.yaml              # demo identities and attributes
└── eval/
    ├── v1/cases.jsonl
    ├── v1/visibility.yaml       # human-labelled visible document set per principal
    └── DATASET_CARD.md
```

### Case schema (v1)

```json
{
  "id": "hr-volunteer-days-eu-001",
  "split": "test",
  "query": "How many paid volunteer days do EU employees receive?",
  "principal": "alice-engineer",
  "evidence": [
    {
      "document": "hr-volunteer-policy",
      "version": 2,
      "section": "Eligibility > European Union",
      "quote": "Employees based in the EU receive two paid volunteer days per calendar year."
    }
  ],
  "expected_facts": ["two paid volunteer days per calendar year"],
  "must_abstain": false,
  "unauthorized_documents": [],
  "hard_negative_documents": ["hr-volunteer-policy-us"],
  "tags": ["policy", "region-scope", "single-hop"]
}
```

Rules:

- **Evidence is recorded as a span, not a chunk ID.** The evaluator locates `quote` inside the normalized text of that document version to get character offsets. Any chunk that overlaps the offsets counts as relevant. This lets different chunking strategies be compared on the same labels. A case whose quote cannot be found fails validation.
- `unauthorized_documents` lists documents the principal may not see and that the query is designed to tempt. These cases feed the security gate.
- `hard_negative_documents` are visible or out-of-scope documents that look relevant but are wrong. These cases feed the quality metrics.
- `must_abstain: true` cases have empty `evidence`.
- **No time field yet.** A case cannot ask "as of" a date until open question Q11 settles whether that selects a historical version or only filters the current one. The field is added to this schema, the API and the oracle in the same change, never to the dataset alone.
- `visibility.yaml` is labelled by hand, separately from the policy compiler. The security gate compares against it; it never compares the compiler with itself.
- **Gate granularity.** Today the gate compares returned *documents* against the visible set, which covers cross-tenant and cross-principal leakage. It does not yet catch a result from the wrong document version or from a chunk that is restricted inside a visible document. M2 extends the labels and the gate to version and chunk granularity, and reports authorization failures separately from scope failures (region, validity).

### Size and composition (v1 target)

- 50–100 fictional documents, 2 tenants, 4 departments, 2 projects, at least 2 documents that come in several versions.
- At least 100 cases. At least 25 of them are authorization negatives: cross-tenant cases and same-tenant cases with the wrong department, project or clearance. Scope negatives (region, validity) are counted separately.
- Tags cover single-hop, multi-section, version selection, abbreviations and synonyms, numbers, no-answer, prompt-injection documents.
- About 30% `dev` and 70% `test`. Tuning (RRF `k`, candidate counts, chunk size) uses `dev` only. Published numbers come from `test` only.

### Guarding against an easy dataset

LLM-assisted drafting tends to produce queries that repeat document wording. For every case the validator computes the lexical overlap between the query and the evidence, reports the distribution, and requires at least 30% of cases in the lowest overlap band (paraphrases). Results are also reported per tag.

## 3. Configurations compared

| Name | Sparse | Dense | Fusion | Rerank |
|---|---|---|---|---|
| `sparse-only` | FTS | – | – | – |
| `dense-only` | – | exact | – | – |
| `hybrid-rrf` | FTS | exact | RRF | – |
| `hybrid-rrf-rerank` | FTS | exact | RRF | cross-encoder |

Reference row, which is not a system configuration: `bm25-reference`. It shows how far PostgreSQL FTS falls short of a ranker that uses corpus statistics, so readers can see where a hybrid gain comes from (ADR-0002). How it stays comparable:

- The CLI reads the principal's chunks from `GET /api/v1/retrieval/chunks`, which runs through the same compiled predicate as the search channels and needs the `debug` scope. The reference therefore ranks exactly the rows the system could have returned, and the evaluation still never touches the database.
- Okapi BM25 with `k1 = 1.2`, `b = 0.75`, IDF computed over that principal's authorized chunks, and a floor that keeps IDF positive for very common terms.
- Tokens are lowercased, English stopwords are removed and words are Snowball-stemmed. This approximates PostgreSQL's `english` configuration; it does not reproduce it exactly.
- Ties keep corpus order (document key, version, ordinal), the same rule the SQL channels use.

Every configuration is a serialized `RetrievalPlan` and its hash is stored with the results.

## 4. Metrics

| Layer | Metrics | Gate in CI? |
|---|---|---|
| Security | unauthorized candidates, unauthorized citations, cross-tenant candidates | **yes, must be 0** |
| Retrieval | Recall@5, Recall@10, MRR@10, nDCG@10 | yes, no regression beyond threshold on the CI subset |
| Hard negatives | rank of the first tempting wrong document; how often it ranks above the first correct evidence | reported |
| Rerank | metric delta vs `hybrid-rrf`, p50/p95 latency delta | reported |
| Context | gold evidence coverage, duplicate ratio, context tokens | reported |
| Answer (when generation is on) | citation validity, abstention accuracy (precision/recall on `must_abstain`), fact recall by exact or normalized match | reported |
| Operations | p50/p95 stage latency, degraded-query rate | degraded rate must be 0 for a valid run |

Answer faithfulness scored by an LLM judge is out of scope for v0.1. If it is added later it will be labelled as a judge metric, its model and prompt version will be recorded, and it will never gate CI.

## 5. Statistics

- Metrics are reported with 95% percentile bootstrap intervals (resampling cases, 10,000 samples, fixed seed recorded in `run.json`).
- nDCG uses binary gain per evidence span: a result earns gain only for spans no higher-ranked result already covered, so several chunks of one paragraph do not count as several relevant results.
- Headline tables and comparisons use the `test` split only. The `dev` split is reported under a separate heading marked "not a result".
- Two configurations are compared with a paired bootstrap on the per-query differences. A gain is only called an "improvement" if the interval excludes zero. Otherwise the report says "no detectable difference".
- If hybrid does not beat the best single channel, the report says so and includes a failure analysis.

## 6. Reproducibility and integrity

Each run writes `results/<run-id>/`:

- `run.json` — dataset version, git SHA, `RetrievalPlan` and its hash, `policy_version`, `chunker_version`, embedding and reranker model with revision, hardware, date, seed, warmup count;
- `cases.jsonl` — per-case ranks, metrics and any degraded flags;
- `report.md` — rendered from the two files above, never edited by hand.

Published runs are produced by the CI runner (Linux x86_64). Keyword and BM25 rankings are bit-identical across machines; dense rankings can swap near-tied candidates across CPU architectures because ONNX Runtime uses different vector kernels, so a local run may differ from a published one by a case. `benchmarks/README.md` records the observed size of that effect.

Rules: no case is removed after a failure, all configurations are published rather than just the best one, and every number in the README links to a committed or released report. Runs on the demo corpus are labelled "demo benchmark" and make no claim about production quality.

## 7. CI

- **Every PR:** schema validation of the dataset, a deterministic retrieval eval on a small fixed subset (precomputed corpus embeddings; the CPU model-service container embeds queries), and the security gate on every authorization case.
- **Manual or nightly:** the full `test` split across all four configurations, plus answer metrics when a local chat model is available.
