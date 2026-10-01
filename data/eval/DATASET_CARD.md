# Dataset card — Grounded Access evaluation set v2

## What it is

A fictional enterprise knowledge base for two tenants and a set of retrieval cases labelled by hand. It exists to compare retrieval strategies and to test that authorization holds; it says nothing about retrieval quality on real enterprise data.

| | Count |
|---|---|
| Documents | 28 (24 Northstar Cloud, 4 Orbit Labs); one Northstar document has two versions |
| Cases | 108: 77 answerable, 31 that must find nothing |
| Split | 78 `test`, 30 `dev` |
| Principals | bob-support 34, alice-engineer 33, carol-manager 26, dave-contractor 8, mallory-outsider 7 |
| Cases with hard negatives | 29 |
| Authorization negatives | 30: 7 cross-tenant, 23 inside the tenant (clearance 10, project 6, department 7) |
| Cases with evidence in more than one place | 3 |

### Versions of this dataset

| Version | Contents | Reports that use it |
|---|---|---|
| v1 | 21 unlabelled documents, 70 cases; authorization is tenant isolation only | `m1a-baseline`, `m1b-hybrid` (tag `v0.1.0-alpha.1`) |
| v2 | v1 plus access labels, 7 restricted look-alike documents, one principal with public clearance and 38 authorization cases | reports from M2 on |

The 70 cases of v1 are unchanged in v2. Their numbers can still move, because principals with access to the new documents now have more candidates to rank. Numbers from v1 and v2 reports are therefore not comparable.

## Sources and licence

Every document was written for this project and describes companies that do not exist. No text comes from a real company, employer, customer or public policy. Documents and labels are published under CC BY 4.0.

Drafting was assisted by a language model; every document, quote and label was then read and checked by hand, and `ga-eval validate` checks the mechanical parts (see below).

## How cases are labelled

- **Evidence** is a document key, a version and an exact quote. The quote must occur exactly once in the normalized document text, which lets the evaluator map it to character offsets and then to whatever chunks the system produced.
- **Hard negatives** are visible documents that look relevant but answer a different question: the search-cluster runbook for a billing-database question, SLA credits for a refund question, relocation for a travel question.
- **Unauthorized documents** name what an authorization negative is trying to reach. The security gate does not rely on them alone: every returned chunk is checked against `visibility.yaml`.
- **Access labels** live in the manifests: a classification, the departments allowed to read a document, and the projects that give access to it. The existing Northstar documents are `internal` (two are `public`), so the three original principals see them as before. The restrictions come from new documents written to look like existing ones: a confidential compensation document that repeats the on-call allowance topic, project documents about a billing-database migration and an unannounced product, department-only documents for support and engineering, and a restricted document nobody in the demo may read.
- **`visibility.yaml`** lists, per principal, the documents it may see. It was written by reading the labels and the principals' attributes, not by running the policy compiler, so the gate compares the system with an independent statement of the rules.
- **Version history.** `sales-pricing-guide` has two versions. Version 1 was readable by everyone internal; version 2 changes the prices and restricts the document to sales and support. Cases ask for the current prices, and principals outside those departments must get nothing, including nothing from version 1.
- **Tags** describe what makes a case hard: `paraphrase` (42), `numbers` (33), `no-answer` (31), `hard-negative` (27), `lexical` (8), `abbreviation` (6), `cross-tenant` (7), `multi-section` / `multi-document` (3). Authorization cases carry `authorization` (38) and the rule they exercise: `clearance` (19), `department` (13), `project` (10), `version` (6).

## Guarding against an easy dataset

Queries drafted with help from a model tend to repeat the document's words. The validator measures how many of a query's content tokens appear in its evidence:

| Band | Overlap | Answerable cases |
|---|---|---|
| low (paraphrase) | < 1/3 | 34 |
| mid | 1/3 – 2/3 | 31 |
| high (keyword match) | ≥ 2/3 | 12 |

`ga-eval validate` fails if fewer than 30% of answerable cases are in the low band, if there are fewer than 25 authorization negatives, or if a case cites a document version that has been replaced.

## Known limitations

- **Small.** 57 answerable `test` cases put one case at just under 2 points of Recall; differences smaller than the reported intervals are not findings.
- **Authorization negatives measure leakage, not abstention.** A negative case passes the gate when nothing unauthorized is returned. Whether the system then says "no answer" is not measured until answers exist (M3).
- **No chunk-level labels.** Labels apply to a whole document version, so the gate checks each returned chunk for its document and version.
- **English only**, Markdown only. One document has a version history; there are no time-dependent cases until open question Q11 decides what "as of" means.
- **Few multi-hop cases.** Only 3 cases need evidence from more than one place.
- **Written by one author**, so vocabulary and style are narrower than a real knowledge base.

## Changing the dataset

Never delete a case because a strategy fails it. Add cases, re-run `ga-eval validate`, and commit a new benchmark run so the effect of the change is visible next to the old numbers.
