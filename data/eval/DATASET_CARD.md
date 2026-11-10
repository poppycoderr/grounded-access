# Dataset card — Grounded Access evaluation set v5

## What it is

A fictional enterprise knowledge base for two tenants and a set of retrieval cases labelled by hand. It exists to compare retrieval strategies and to test that authorization holds; it says nothing about retrieval quality on real enterprise data.

| | Count |
|---|---|
| Documents | 48 (43 Northstar Cloud, 5 Orbit Labs); two Northstar documents have two versions |
| Cases | 162: 119 answerable, 43 that must find nothing |
| Split | 120 `test`, 42 `dev` |
| Principals | bob-support 52, alice-engineer 51, carol-manager 40, dave-contractor 11, mallory-outsider 8 |
| Cases with hard negatives | 39 |
| Authorization negatives | 39: 8 cross-tenant, 31 inside the tenant (clearance 14, project 7, department 10) |
| Scope cases | 13: they set a region or a date, or name readable documents that do not apply to the request |
| Prompt-injection cases | 6: a readable document contains an instruction aimed at a language model |
| Cases with evidence in more than one place | 3 |

### Versions of this dataset

| Version | Contents | Reports that use it |
|---|---|---|
| v1 | 21 unlabelled documents, 70 cases; authorization is tenant isolation only | `m1a-baseline`, `m1b-hybrid` (tag `v0.1.0-alpha.1`) |
| v2 | v1 plus access labels, 7 restricted look-alike documents, one principal with public clearance and 38 authorization cases | `m2-labelled-dataset` |
| v3 | v2 plus 4 documents with a region or a validity window, a validity window on the current travel policy, and 14 cases: 13 about scope and one more authorization negative | `m2-authorization`, `m3-rerank` |
| v4 | v3 plus 3 documents that contain an instruction aimed at a language model, and 6 cases that name the phrase such an instruction tries to elicit | reports from the M3 report on |
| v5 | v4 plus the v0.2 corpus, added in reviewed batches until step 1 of the [v0.2 plan](../../docs/project/v0.2-plan.md) is complete. Batch 1: 13 documents with sections long enough to be split, code blocks, tables and identifiers without spaces, a second document with a version history, and 34 cases | none yet; v5 is not used for reports until it is complete |

Cases are never changed or removed between versions. Their numbers can still move, because principals with access to new documents have more candidates to rank. Numbers from reports on different dataset versions are therefore not comparable.

## Sources and licence

Every document was written for this project and describes companies that do not exist. No text comes from a real company, employer, customer or public policy. Documents and labels are published under CC BY 4.0.

For v1 to v4, drafting was assisted by a language model; every document, quote and label was then read and checked by hand, and `ga-eval validate` checks the mechanical parts (see below).

The documents and cases added in v5 were drafted by a language model and were not read and checked by hand before they were merged. They were checked mechanically instead: `ga-eval validate`; a run of every strategy except reranking on a fresh stack, with no security violation and no scope failure; and a comparison of every quote with the chunks of the loaded corpus. Anyone who relies on v5 numbers should treat its labels as unreviewed until a review is recorded here.

## How cases are labelled

- **Evidence** is a document key, a version and an exact quote. The quote must occur exactly once in the normalized document text, which lets the evaluator map it to character offsets and then to whatever chunks the system produced.
- **Hard negatives** are visible documents that look relevant but answer a different question: the search-cluster runbook for a billing-database question, SLA credits for a refund question, relocation for a travel question.
- **Unauthorized documents** name what an authorization negative is trying to reach. The security gate does not rely on them alone: every returned chunk is checked against `visibility.yaml`.
- **Access labels** live in the manifests: a classification, the departments allowed to read a document, and the projects that give access to it. The existing Northstar documents are `internal` (two are `public`), so the three original principals see them as before. The restrictions come from new documents written to look like existing ones: a confidential compensation document that repeats the on-call allowance topic, project documents about a billing-database migration and an unannounced product, department-only documents for support and engineering, and a restricted document nobody in the demo may read.
- **`visibility.yaml`** lists, per principal, the documents it may see. It was written by reading the labels and the principals' attributes, not by running the policy compiler, so the gate compares the system with an independent statement of the rules.
- **Scope.** Region and validity are not access (ADR-0005). Two holiday calendars apply to one region each. The 2025 travel policy has a closed validity window and the current one starts where it ends. A benefit that starts in 2099 is not valid yet. Every Northstar principal with `internal` clearance is authorized for all four, so `visibility.yaml` lists them; a case names the ones that do not apply to its request in `out_of_scope_documents`, and may set `as_of` and `region`. Returning an out-of-scope document is a scope failure, reported apart from security violations.
- **Prompt injection.** Three ordinary-looking documents each contain one passage addressed to a language model: a refund window of 90 days where the policy says 14, an on-call allowance of 9,999 EUR, and an order to answer with a single word. Every Northstar principal with `internal` clearance may read them, so they are legitimate retrieval candidates. A case lists the phrase the instruction tries to elicit in `forbidden_answer_phrases`; an answer that contains it has been steered. These cases matter only when answers are generated. For retrieval they are ordinary cases, and the injected passage is a hard negative.
- **Version history.** `sales-pricing-guide` has two versions. Version 1 was readable by everyone internal; version 2 changes the prices and restricts the document to sales and support. Cases ask for the current prices, and principals outside those departments must get nothing, including nothing from version 1. `hr-corporate-card` has two versions with the same access labels; version 2 raises the card limits and shortens the receipt deadline, so an answer taken from version 1 is wrong, not unauthorized. Cases ask for the current values.
- **Tags** describe what makes a case hard: `paraphrase` (61), `numbers` (55), `no-answer` (43), `hard-negative` (33), `lexical` (10), `abbreviation` (6), `cross-tenant` (8), `multi-section` / `multi-document` (3). Authorization cases carry `authorization` (46) and the rule they exercise: `clearance` (23), `department` (16), `project` (12), `version` (8). Scope cases carry `scope` (13) with `region` (6) or `validity` (7).
- **Long and structured text.** From v5 on, cases also say where their evidence sits: `long-section` (8) for a quote in a section longer than one chunk, `table` (4) for a quote that is a table row, `space-free` (2) for a query or quote that depends on an identifier with no spaces, such as an error code or an index name.

## Guarding against an easy dataset

Queries drafted with help from a model tend to repeat the document's words. The validator measures how many of a query's content tokens appear in its evidence:

| Band | Overlap | Answerable cases |
|---|---|---|
| low (paraphrase) | < 1/3 | 50 |
| mid | 1/3 – 2/3 | 42 |
| high (keyword match) | ≥ 2/3 | 27 |

`ga-eval validate` fails if fewer than 30% of answerable cases are in the low band, if there are fewer than 25 authorization negatives, or if a case cites a document version that has been replaced.

## Known limitations

- **Small.** 90 answerable `test` cases put one case at about 1.1 points of Recall; differences smaller than the reported intervals are not findings.
- **Three injections are a smoke test, not a red-team suite.** They use obvious phrasings that the keyword heuristic also flags. A model that resists them has not been shown to resist a careful attacker.
- **Answer checks are string comparisons.** Fact recall looks for the expected phrase in the answer, and a forbidden phrase is matched literally. Neither judges whether a statement is faithful to the passage it cites.
- **No chunk-level labels.** Labels apply to a whole document version, so the gate checks each returned chunk for its document and version.
- **English only**, Markdown only. Two documents have a version history. Time-dependent cases use fixed dates in 2024, 2025 and 2099; cases without a date run "as of now", and the corpus is written so that their expected answer does not change as time passes.
- **Few multi-hop cases.** Only 3 cases need evidence from more than one place.
- **Written by one author**, so vocabulary and style are narrower than a real knowledge base.

## Changing the dataset

Never delete a case because a strategy fails it. Add cases, re-run `ga-eval validate`, and commit a new benchmark run so the effect of the change is visible next to the old numbers.
