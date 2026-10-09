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
├── manifests/northstar.yaml     # document access labels and version history
├── principals.yaml              # demo identities and attributes
└── eval/
    ├── v4/cases.jsonl
    ├── v4/visibility.yaml       # human-labelled visible document set per principal
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
- **Time and region.** A case may set `as_of` and `region`, the scope of its request (ADR-0005), and may list `out_of_scope_documents`: documents its principal is authorized for but that do not apply to the request. The validator rejects an out-of-scope document the principal cannot read, because that would be an authorization case.
- `visibility.yaml` is labelled by hand, separately from the policy compiler. The security gate compares against it; it never compares the compiler with itself.
- **Gate granularity.** The gate checks every returned chunk twice: its document must be in the principal's visible set, and it must come from the document's current version. A chunk of a replaced version is a violation even when the document is visible, because labels belong to versions.
- **Listing check.** Before any query runs, the runner lists every chunk each principal can reach and compares the documents with the visible set. Anything extra is a violation, so the gate also covers documents that no query happens to retrieve. Anything missing means the labels and the system disagree, and the run stops.
- **Scope failures are separate.** A returned document that the case lists as out of scope is a scope failure. It is counted per strategy in its own report section and makes the run exit with code 3; a security violation exits with code 2. The two are never added together. `visibility.yaml` is about access only, and the listing check drops the scope to compare it.
- **Reference under the same scope.** The BM25 reference lists the principal's chunks under the scope of each case, so it ranks what the system could have returned for that request.
- **Not yet in the gate.** Labels apply to whole document versions, so there is nothing to check inside a version; chunk-level labels are deferred to v0.2.

### Size and composition (v1 target)

- 50–100 fictional documents, 2 tenants, 4 departments, 2 projects, at least 2 documents that come in several versions.
- At least 100 cases. At least 25 of them are authorization negatives: cross-tenant cases and same-tenant cases with the wrong department, project or clearance; the validator enforces the 25. Scope negatives (region, validity) are counted separately.
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

Every configuration is a serialized `RetrievalPlan`. `run.json` stores each strategy's plan and hash, and the report prints the hashes. A response marked `degraded` (for example hybrid answered without the dense channel) is never scored, because it does not measure the strategy it is filed under. The request is sent again, up to three times in all: on a busy machine one rerank call can exceed its timeout. Three degraded answers in a row abort the run. `run.json` records how many requests were repeated (`repeated_degraded_requests`), and the report says so when the number is not zero.

## 4. Metrics

| Layer | Metrics | Gate in CI? |
|---|---|---|
| Security | unauthorized candidates, unauthorized citations, cross-tenant candidates | **yes, must be 0** |
| Retrieval | Recall@5, Recall@10, MRR@10, nDCG@10 | yes, no regression beyond threshold on the CI subset |
| Hard negatives | rank of the first tempting wrong document; how often it ranks above the first correct evidence | reported |
| Rerank | paired metric difference against `hybrid-rrf` and `dense-only`; p50 and p95 request latency per strategy, measured by the evaluation client on the machine named in the report | reported |
| Context | gold evidence coverage, duplicate ratio, context tokens | reported |
| Answer (when generation is on) | share of answers that cite the labelled evidence, citation precision, abstention precision and recall on `must_abstain`, fact recall by normalized match, prompt-injection cases steered into a forbidden phrase | reported by `ga-eval answers`, a local run that names its chat model; never produced by CI |
| Operations | p50/p95 stage latency, degraded-query rate | degraded rate must be 0 for a valid run |

Answer faithfulness scored by an LLM judge is out of scope for v0.1. If it is added later it will be labelled as a judge metric, its model and prompt version will be recorded, and it will never gate CI.

### Answer metrics

`ga-eval answers` calls `/api/v1/query` once per case with the case's principal and scope, and scores the response:

- **Cites the labelled evidence.** A cited passage counts when it overlaps a labelled span of the same document version. Citation precision is the share of cited passages that do.
- **Fact recall.** The share of a case's `expected_facts` found in the answer after lowercasing, removing thousands separators and collapsing whitespace. A case that was not answered scores 0.
- **Abstention.** Recall is the share of `must_abstain` cases answered with `no_answer`; precision is the share of `no_answer` responses that were `must_abstain` cases.
- **Prompt injection.** A case may list `forbidden_answer_phrases`, the phrases an instruction planted in a readable document tries to elicit. A case whose answer contains one has been steered.
- **Security and scope.** Evidence in a response is checked against visibility and scope exactly as retrieval results are.

These are string and span comparisons. They do not judge whether a statement is faithful to the passage it cites, and no LLM judge is used. The report names the chat model, the prompt version and the CPU, and says that it is not a CI result.

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

`run.json` records the chunker versions seen in the corpus. The runner refuses to start when the corpus mixes chunker releases (for example after an upgrade without a fresh load), because the results would compare two chunkings.

Published runs are produced by the CI runner (Linux x86_64). Keyword and BM25 rankings are bit-identical across machines; dense rankings can swap near-tied candidates between CPUs because ONNX Runtime uses different vector kernels. Across architectures that can move a case; between hosted x86_64 runners it has only reordered near-ties without changing a metric. `run.json` records the CPU model. `benchmarks/README.md` records the observed size of that effect.

Rules: no case is removed after a failure, all configurations are published rather than just the best one, and every number in the README links to a committed or released report. Runs on the demo corpus are labelled "demo benchmark" and make no claim about production quality.

## 7. CI

- **Every PR:** schema validation of the dataset, a deterministic retrieval eval on a small fixed subset (precomputed corpus embeddings; the CPU model-service container embeds queries), and the security gate on every authorization case.
- **Local only:** answer metrics, with `ga-eval answers` against a control plane that has a chat model configured. CI has no chat model. It tests the deterministic parts of the answering path (context building, citation validation, refusal) with a scripted model, and the scoring functions with fabricated responses.
