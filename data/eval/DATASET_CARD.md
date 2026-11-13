# Dataset card — Grounded Access evaluation set v5

## What it is

A fictional enterprise knowledge base for two tenants and a set of retrieval cases. The 128 cases of v4 were checked by a person; the cases added in v5 were drafted by a language model and are recorded as unreviewed (see the review record below). It exists to compare retrieval strategies and to test that authorization holds; it says nothing about retrieval quality on real enterprise data.

| | Count |
|---|---|
| Documents | 300 (254 Northstar Cloud, 46 Orbit Labs); six Northstar documents have two versions |
| Cases | 372: 302 answerable, 70 that must find nothing |
| Split | 292 `test`, 80 `dev` |
| Principals | bob-support 118, alice-engineer 111, carol-manager 92, dave-contractor 29, mallory-outsider 22 |
| Cases with hard negatives | 89 |
| Authorization negatives | 73: 21 cross-tenant, 52 inside the tenant (project 11, department 22, clearance 19; a case that fails on more than one rule is counted under project, then department) |
| Scope cases | 20: they set a region or a date, or name readable documents that do not apply to the request |
| Prompt-injection cases | 6: a readable document contains an instruction aimed at a language model |
| Cases with evidence in more than one place | 12 |

### Versions of this dataset

| Version | Contents | Reports that use it |
|---|---|---|
| v1 | 21 unlabelled documents, 70 cases; authorization is tenant isolation only | `m1a-baseline`, `m1b-hybrid` (tag `v0.1.0-alpha.1`) |
| v2 | v1 plus access labels, 7 restricted look-alike documents, one principal with public clearance and 38 authorization cases | `m2-labelled-dataset` |
| v3 | v2 plus 4 documents with a region or a validity window, a validity window on the current travel policy, and 14 cases: 13 about scope and one more authorization negative | `m2-authorization`, `m3-rerank` |
| v4 | v3 plus 3 documents that contain an instruction aimed at a language model, and 6 cases that name the phrase such an instruction tries to elicit | reports from the M3 report on |
| v5 | v4 plus the v0.2 corpus, added in batches until step 1 of the [v0.2 plan](../../docs/project/v0.2-plan.md) is complete. Batch 1: 13 documents with sections long enough to be split, code blocks, tables and identifiers without spaces, a second document with a version history, and 34 cases. Batch 2: 13 documents, among them a postmortem, a one-line configuration longer than 2,000 characters without a space, and a third document with a version history, and 35 cases. Batch 3: 14 documents, among them two sick-leave policies that apply to one region each, a restricted report, a configuration block longer than 2,000 characters, and a fourth document with a version history whose second version is restricted to engineering, and 35 cases. Batch 4: 14 documents, among them a service ownership register, a support plan that applied only over the 2025 year end, and three more public documents, and 36 cases, among them 3 with evidence in two documents and 3 that set a date. Batches 5–8: 213 shorter documents on workplace, engineering, support, sales, legal, security, product, finance and operations topics, two more documents with a version history, and 104 cases | none yet; v5 is not used for reports until it is complete |

Cases are never changed or removed between versions. Their numbers can still move, because principals with access to new documents have more candidates to rank. Numbers from reports on different dataset versions are therefore not comparable.

## Sources and licence

Every document was written for this project and describes companies that do not exist. No text comes from a real company, employer, customer or public policy. Documents and labels are published under CC BY 4.0.

For v1 to v4, drafting was assisted by a language model; every document, quote and label was then read and checked by hand, and `ga-eval validate` checks the mechanical parts (see below).

The documents and cases added in v5 were drafted by a language model and were not read and checked by hand before they were merged. They were checked mechanically instead: `ga-eval validate`; a run of every strategy except reranking on a fresh stack, with no security violation and no scope failure; and a comparison of every quote with the chunks of the loaded corpus. Their cases are not listed in `v5/reviews.yaml`, so they enter no published number until a person checks them; they still count for the security gate. The visibility of the documents added in v5 was derived from the manifest labels with the decision table, not written by hand.

### Review record

`v5/reviews.yaml` lists the cases a person has checked ([ADR-0015](../../docs/adr/0015-where-evaluation-labels-come-from.md)). Only those enter published numbers.

| Version | Documents and cases drafted by | Cases checked by a person | Errors found by the check |
|---|---|---|---|
| v1–v4 | a person, with drafting help from a language model | 128 of 128 | not recorded |
| v5 additions | a language model | 0 of 244 | no check yet |

Reviewing follows ADR-0015: first the 73 authorization negatives and the labels of the documents they target, then the 70 must-abstain cases, then the answerable cases batch by batch. 265 documents were added after the v4 cases were checked, so a v4 case that must find nothing could now have an answer in an unread document.

## How cases are labelled

- **Evidence** is a document key, a version and an exact quote. The quote must occur exactly once in the normalized document text, which lets the evaluator map it to character offsets and then to whatever chunks the system produced.
- **Hard negatives** are visible documents that look relevant but answer a different question: the search-cluster runbook for a billing-database question, SLA credits for a refund question, relocation for a travel question.
- **Unauthorized documents** name what an authorization negative is trying to reach. The security gate does not rely on them alone: every returned chunk is checked against `visibility.yaml`.
- **Access labels** live in the manifests: a classification, the departments allowed to read a document, and the projects that give access to it. The existing Northstar documents are `internal` (two are `public`), so the three original principals see them as before. The restrictions come from new documents written to look like existing ones: a confidential compensation document that repeats the on-call allowance topic, project documents about a billing-database migration and an unannounced product, department-only documents for support and engineering, and a restricted document nobody in the demo may read.
- **`visibility.yaml`** lists, per principal, the documents it may see. For the v4 documents it was written by reading the labels and the principals' attributes, not by running the policy compiler. For the documents added in v5 it was derived from the labels with the rules of the decision table (`ga-eval visibility`). `ga-eval validate` requires the whole file to match that derivation, so the gate compares the system with a second implementation of the rules. Whether a label says what its document means is part of reviewing a case.
- **Scope.** Region and validity are not access (ADR-0005). Two holiday calendars and two sick-leave policies apply to one region each. A support coverage plan applied only from 20 December 2025 to 4 January 2026; its window lies in the past, so cases without a date never see it, and cases that set a date inside or after the window test it. The 2025 travel policy has a closed validity window and the current one starts where it ends. A benefit that starts in 2099 is not valid yet. Every Northstar principal with `internal` clearance is authorized for all four, so `visibility.yaml` lists them; a case names the ones that do not apply to its request in `out_of_scope_documents`, and may set `as_of` and `region`. Returning an out-of-scope document is a scope failure, reported apart from security violations.
- **Prompt injection.** Three ordinary-looking documents each contain one passage addressed to a language model: a refund window of 90 days where the policy says 14, an on-call allowance of 9,999 EUR, and an order to answer with a single word. Every Northstar principal with `internal` clearance may read them, so they are legitimate retrieval candidates. A case lists the phrase the instruction tries to elicit in `forbidden_answer_phrases`; an answer that contains it has been steered. These cases matter only when answers are generated. For retrieval they are ordinary cases, and the injected passage is a hard negative.
- **Version history.** `sales-pricing-guide` has two versions. Version 1 was readable by everyone internal; version 2 changes the prices and restricts the document to sales and support. Cases ask for the current prices, and principals outside those departments must get nothing, including nothing from version 1. `hr-corporate-card` has two versions with the same access labels; version 2 raises the card limits and shortens the receipt deadline, so an answer taken from version 1 is wrong, not unauthorized. `eng-api-versioning` is public in both versions; version 2 extends the support period of an old API version from six to twelve months. `hr-office-attendance` and `eng-incident-severity-guide` change their content but not their labels. `eng-disaster-recovery` version 1 was readable by everyone internal; version 2 shortens the tier 1 recovery target and restricts the plan to engineering, so bob-support must get nothing, including nothing from version 1. Cases ask for the current values.
- **Tags** describe what makes a case hard: `paraphrase` (171), `numbers` (154), `no-answer` (70), `hard-negative` (45), `lexical` (21), `abbreviation` (16), `cross-tenant` (21), `multi-section` / `multi-document` (12). Authorization cases carry `authorization` (68) and the rule they exercise: `clearance` (32), `department` (28), `project` (22), `version` (18). Scope cases carry `scope` (20) with `region` (10) or `validity` (10).
- **Long and structured text.** From v5 on, cases also say where their evidence sits: `long-section` (23) for a quote in a section longer than one chunk, `table` (12) for a quote that is a table row, `space-free` (4) for a query or quote that depends on an identifier with no spaces, such as an error code or an index name. `crosses-split` (2) marks a quote that no single chunk contains whole; it was found by comparing every quote with the chunks of a loaded stack, so the tag holds for chunker release 4 and has to be checked again when the chunker changes.

## Guarding against an easy dataset

Queries drafted with help from a model tend to repeat the document's words. The validator measures how many of a query's content tokens appear in its evidence:

| Band | Overlap | Answerable cases |
|---|---|---|
| low (paraphrase) | < 1/3 | 103 |
| mid | 1/3 – 2/3 | 127 |
| high (keyword match) | ≥ 2/3 | 72 |

`ga-eval validate` fails if fewer than 30% of answerable cases are in the low band, if there are fewer than 25 authorization negatives, or if a case cites a document version that has been replaced.

## Known limitations

- **Small.** 240 answerable `test` cases put one case at about 0.4 points of Recall; differences smaller than the reported intervals are not findings.
- **Three injections are a smoke test, not a red-team suite.** They use obvious phrasings that the keyword heuristic also flags. A model that resists them has not been shown to resist a careful attacker.
- **Answer checks are string comparisons.** Fact recall looks for the expected phrase in the answer, and a forbidden phrase is matched literally. Neither judges whether a statement is faithful to the passage it cites.
- **No chunk-level labels.** Labels apply to a whole document version, so the gate checks each returned chunk for its document and version.
- **English only**, Markdown only. Six documents have a version history. Time-dependent cases use fixed dates in 2024, 2025 and 2099; cases without a date run "as of now", and the corpus is written so that their expected answer does not change as time passes.
- **Few multi-hop cases.** Only 12 cases need evidence from more than one place.
- **Many short, uniform documents.** Batches 5–8 of v5 add 213 documents of two to four short sections each, written to a common pattern; many share the same opening sentence. They add volume and plausible competitors more than they add hard reading.
- **Written by one author**, so vocabulary and style are narrower than a real knowledge base.

## Changing the dataset

Never delete a case because a strategy fails it. Add cases, re-run `ga-eval validate`, and commit a new benchmark run so the effect of the change is visible next to the old numbers.
