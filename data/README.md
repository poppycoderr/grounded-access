# Demo data

Everything in this directory is fictional. Northstar Cloud and Orbit Labs do not exist, and no document here comes from a real company, customer or employer.

| Path | Contents |
|---|---|
| `corpus/<tenant>/` | The documents themselves, in Markdown |
| `manifests/<tenant>.yaml` | Document key, title and file for ingestion |
| `principals.yaml` | Demo identities and the attributes that become token claims |
| `eval/v1/cases.jsonl` | Evaluation cases: query, principal, evidence quotes, expectations, tags |
| `eval/v1/visibility.yaml` | Hand-labelled visible documents per principal, used by the security gate |
| `demo-keys/` | The public demo signing key pair — insecure by design, local demos only |

The corpus and the evaluation labels are published under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The code in this repository is Apache-2.0.

Orbit Labs deliberately shares vocabulary with Northstar Cloud ("volunteer days", "billing database") so that cross-tenant isolation is tested with wording that would otherwise match.

Validate the dataset after any change:

```bash
uv run --project packages/evaluation ga-eval validate
```
