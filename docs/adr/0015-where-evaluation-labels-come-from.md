# ADR-0015: Where evaluation labels come from

- Status: Accepted
- Date: 2026-11-13

## Context

The project makes two claims. Authorization is compiled into SQL, and every result is judged against a labelled dataset that is independent of the system. The second claim holds only if a person has read the labels. For dataset v1 to v4 that is the case: a language model helped draft documents and cases, and a person then read and checked every document, quote and label (dataset card).

Dataset v5 adds 265 documents and 244 cases that a language model drafted and nobody has read (review 2026-11-10, P0-1). The project had no rule for what makes evaluation data evidence. Rule 7 asks for evaluation evidence with retrieval changes, but says nothing about the data itself. Three gaps follow:

- **Visibility can be derived instead of labelled.** For part of v5, `visibility.yaml` was computed from the manifest labels with the rules of the decision table. The security gate then shows that the policy compiler agrees with a second implementation of those rules. It cannot show that a label says what its author meant.
- **Must-abstain cases depend on every document.** A case that must find nothing is wrongly labelled if any document in the corpus answers it, including one nobody has read.
- **"Checked by hand" stops being true.** The README and the docs describe the whole dataset that way.

Three options were considered. A person could read all of v5 before it is merged; that is the cleanest, but the reading time blocks every later step. v5 could be merged as unreviewed, with the claim weakened for every number. Or the review could be recorded case by case, with published numbers drawn only from checked cases. This ADR takes the third option.

## Decision

- **A case counts as checked only when a person checked it.** The person read the documents the case names, its quotes, expected facts, hard negatives and unauthorized documents, and confirmed every label. For a must-abstain case, they also confirmed that no document they can find answers it. A model may draft a case but may not check one. A script may derive a label, and a derived label says that it was derived.
- **The review is recorded per case, next to the cases.** `data/eval/<version>/reviews.yaml` lists the ids of the checked cases. A case not listed is unreviewed. The review record is left out of the dataset digest: checking a case changes whether its numbers may be published, not what the case expects, so recording a review does not make earlier runs incomparable or invalidate the expected rankings.
- **Published numbers come only from checked cases.** `ga-eval run` scores every case. Its summaries, its paired comparisons and its tag table count only reviewed cases; a separate table, marked as not a result, counts all of them. `ga-eval compare` pairs only cases that were reviewed in both runs. The answer evaluation summarizes reviewed cases only.
- **Unreviewed cases still count for the security gate.** A returned chunk the principal may not read is a violation whoever wrote the case, because the gate checks every result against `visibility.yaml`, not against the case.
- **Visibility is checked against the decision table.** `ga-eval validate` derives each labelled principal's visible documents from the manifest labels with the four rules of the decision table, written in Python apart from the policy compiler, and requires `visibility.yaml` to match. `ga-eval visibility` prints that derivation. For v4 the hand-written file and the derivation agree on every principal. When a person writes visibility by hand, the check catches slips; when it is derived, the dataset card says so. What visibility alone cannot show is whether a manifest label matches the document's text. That is part of checking a case.
- **The dataset card records provenance per version.** For each version it says who drafted documents and cases (a person or a model), how many cases were checked, and how many errors the checks found.
- **Every "by hand" statement names the subset it covers.** When a dataset version contains unreviewed cases, the README and the docs say which cases were checked and how many.
- **No dataset tags.** A report already records the commit and the dataset digest it was scored against, which is enough to check out the exact files again.

The rule becomes non-negotiable rule 9 in `AGENTS.md`: evaluation labels say where they came from, and numbers published as results come only from cases a person checked.

## Consequences

- v5 can be merged before anyone reads it. Its unreviewed cases add load, scale and security-gate coverage straight away. They add nothing to published quality numbers until they are reviewed.
- Headline retrieval numbers keep resting on the v4 cases until v5 cases are reviewed. The corpus is larger, so those numbers move: they measure the old cases against more candidates.
- A reviewed must-abstain case can turn wrong when unreviewed documents are added, because one of them may answer it. The dataset card says how many documents were added after the last review. A must-abstain case that gets an answer is the first thing to check.
- Reviewing is ordered so the most important claims are covered first: the authorization negatives and the labels of the documents they target, then the must-abstain cases, then the answerable cases batch by batch.
- Rule 7 is unchanged. Retrieval changes still need evaluation evidence, and that evidence is now computed on reviewed cases.
