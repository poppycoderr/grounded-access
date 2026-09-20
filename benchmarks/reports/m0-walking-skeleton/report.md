# Retrieval evaluation — dataset v1

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `2f61d23`, 15 cases (dev, test), k=10, policy tenant-only/1, generated 2026-09-20T15:53:55+00:00.

| Strategy | Answerable cases | Recall@5 | Recall@10 | MRR@10 | Security violations |
|---|---|---|---|---|---|
| `sparse-only` | 13 | 0.923 | 0.923 | 0.705 | 0 |
| `dense-only` | 13 | 1.000 | 1.000 | 0.910 | 0 |

## Recall@10 by tag

| Tag | `sparse-only` | `dense-only` |
|---|---|---|
| api | 1.00 (n=2) | 1.00 (n=2) |
| cross-tenant | 1.00 (n=1) | 1.00 (n=1) |
| identical-wording | 1.00 (n=1) | 1.00 (n=1) |
| incident | 1.00 (n=1) | 1.00 (n=1) |
| lexical | 1.00 (n=2) | 1.00 (n=2) |
| multi-section | 1.00 (n=1) | 1.00 (n=1) |
| numbers | 1.00 (n=2) | 1.00 (n=2) |
| paraphrase | 0.75 (n=4) | 1.00 (n=4) |
| policy | 0.67 (n=3) | 1.00 (n=3) |
| runbook | 1.00 (n=2) | 1.00 (n=2) |
| sales | 1.00 (n=1) | 1.00 (n=1) |
| security | 1.00 (n=1) | 1.00 (n=1) |
| single-hop | 0.91 (n=11) | 1.00 (n=11) |
| support | 1.00 (n=2) | 1.00 (n=2) |

## Misses and violations

- `sparse-only` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
