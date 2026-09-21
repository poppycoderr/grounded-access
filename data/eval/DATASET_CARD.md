# Dataset card — Grounded Access evaluation set v1

## What it is

A fictional enterprise knowledge base for two tenants and a set of retrieval cases labelled by hand. It exists to compare retrieval strategies and to test that authorization holds; it says nothing about retrieval quality on real enterprise data.

| | Count |
|---|---|
| Documents | 21 (17 Northstar Cloud, 4 Orbit Labs) |
| Cases | 70: 63 answerable, 7 that must find nothing |
| Split | 52 `test`, 18 `dev` |
| Principals | alice-engineer 23, bob-support 23, carol-manager 18, mallory-outsider 6 |
| Cases with hard negatives | 18 |
| Cross-tenant probes | 6 (3 with identical wording to a Northstar case) |
| Cases with evidence in more than one place | 3 |

## Sources and licence

Every document was written for this project and describes companies that do not exist. No text comes from a real company, employer, customer or public policy. Documents and labels are published under CC BY 4.0.

Drafting was assisted by a language model; every document, quote and label was then read and checked by hand, and `ga-eval validate` checks the mechanical parts (see below).

## How cases are labelled

- **Evidence** is a document key, a version and an exact quote. The quote must occur exactly once in the normalized document text, which lets the evaluator map it to character offsets and then to whatever chunks the system produced.
- **Hard negatives** are visible documents that look relevant but answer a different question: the search-cluster runbook for a billing-database question, SLA credits for a refund question, relocation for a travel question.
- **Unauthorized documents** name what a cross-tenant probe is trying to reach. The security gate does not rely on them alone: every returned document is checked against `visibility.yaml`.
- **Tags** describe what makes a case hard: `paraphrase` (34), `numbers` (25), `hard-negative` (16), `lexical` (7), `abbreviation` (6), `no-answer` (7), `cross-tenant` (6), `multi-section` / `multi-document` (3).

## Guarding against an easy dataset

Queries drafted with help from a model tend to repeat the document's words. The validator measures how many of a query's content tokens appear in its evidence:

| Band | Overlap | Answerable cases |
|---|---|---|
| low (paraphrase) | < 1/3 | 30 |
| mid | 1/3 – 2/3 | 26 |
| high (keyword match) | ≥ 2/3 | 7 |

`ga-eval validate` fails if fewer than 30% of answerable cases are in the low band.

## Known limitations

- **Small.** 47 answerable `test` cases put one case at about 2 points of Recall; differences smaller than the reported intervals are not findings.
- **Tenant-level authorization only.** Every Northstar principal sees every Northstar document. Department, project and clearance negatives arrive with the M2 labels.
- **English only**, Markdown only, one version per document. There are no version conflicts or time-dependent cases until open question Q11 decides what "as of" means.
- **Few multi-hop cases.** Only 3 cases need evidence from more than one place.
- **Written by one author**, so vocabulary and style are narrower than a real knowledge base.

## Changing the dataset

Never delete a case because a strategy fails it. Add cases, re-run `ga-eval validate`, and commit a new benchmark run so the effect of the change is visible next to the old numbers.
