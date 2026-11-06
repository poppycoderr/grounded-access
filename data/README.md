# Demo data

Everything in this directory is fictional. Northstar Cloud and Orbit Labs do not exist, and no document here comes from a real company, customer or employer.

| Path | Contents |
|---|---|
| `corpus/<tenant>/` | The documents themselves, in Markdown |
| `manifests/<tenant>.yaml` | Document key, title, file, access labels, scope (regions, validity) and version history for ingestion |
| `principals.yaml` | Demo identities and the attributes that become token claims |
| `eval/v4/cases.jsonl` | Evaluation cases: query, principal, evidence quotes, expectations, tags |
| `eval/v4/visibility.yaml` | Hand-labelled visible documents per principal, used by the security gate |
| `demo-keys/` | The public demo signing key pair — insecure by design, local demos only |

The corpus and the evaluation labels are published under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The code in this repository is Apache-2.0.

Orbit Labs deliberately shares vocabulary with Northstar Cloud ("volunteer days", "billing database") so that cross-tenant isolation is tested with wording that would otherwise match.

Load the corpus into a freshly started stack. A document with a version history is ingested oldest version first, so loading it again into the same database adds further versions, and the evaluation then stops with a version mismatch.

## Generated distractors

Scale and load runs need more volume than the labelled corpus has. `ga-eval distractors` writes fictional distractor documents into a directory of their own, in the layout of this one (`corpus/`, `manifests/`), plus a `visibility.yaml` per demo principal:

```text
uv run --project packages/evaluation ga-eval distractors --seed 1 --documents 1300 --out results/distractors
uv run --project packages/evaluation ga-eval load --manifests results/distractors
```

- **Deterministic.** A seed always writes the same bytes, so nothing generated is committed; a run names its seed and document count.
- **Distractors only.** Keys start with `distractor-`, and no case cites them. They are not part of the labelled dataset or its digest, and the labelled evaluation must not run on a stack that has them loaded: `visibility.yaml` does not list them, so `ga-eval run` would count every one a principal can see as a security violation.
- **Labelled like real documents.** Every rule of the decision table occurs, about 2% of documents require project `atlas` (a selective filter), and about one section in ten is longer than 400 words, so chunker release 4 has to split it. Their `visibility.yaml` is written by `ga_eval.distractors.visible`, a direct reading of the decision table that does not use the policy compiler.
- **Size.** On the current chunker one document becomes about 7–8 chunks, so about 1,300 documents give the 10,000 chunks of the v0.2 plan.

Validate the dataset after any change:

```bash
uv run --project packages/evaluation ga-eval validate
```
