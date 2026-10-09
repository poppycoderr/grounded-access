# Heading context: choosing a configuration on the dev split

The v0.1 report found a passage that no strategy could retrieve because it depends on its heading and passages were indexed without it ([case 2](../v0.1.md)). Three places could take the heading path of a chunk into account, each behind a switch:

| Switch | Effect |
|---|---|
| `GA_EMBEDDING_CONTEXT` | the heading path is embedded together with the chunk |
| `GA_SPARSE_CONTEXT` | keyword search covers the heading path as well as the chunk |
| `GA_RERANK_CONTEXT` | the cross-encoder sees the heading path in front of the chunk |

Each directory here is one local run of `ga-eval run --split dev` on dataset v4 at commit `3e8d3d0`, with the switches named by the directory. `comparison.md` in a directory is `ga-eval compare baseline <directory> --split dev`: the same strategy on the same 23 answerable cases, paired per case.

| Configuration | `sparse-only` MRR@10 | `dense-only` MRR@10 | `hybrid-rrf` MRR@10 | `hybrid-rrf-rerank` MRR@10 |
|---|---|---|---|---|
| `baseline` | 0.663 | 0.848 | 0.813 | 0.928 |
| `keyword` | 0.704 | 0.848 | 0.857 | 0.928 |
| `embedding` | 0.663 | 0.906 | 0.864 | 0.928 |
| `rerank` | 0.663 | 0.848 | 0.813 | 0.935 |
| `embedding-keyword` | 0.704 | 0.906 | 0.882 | 0.928 |
| `embedding-keyword-rerank` | 0.704 | 0.906 | 0.882 | 0.900 |

Recall@10 does not change for any system strategy in any configuration.

## Decision

`embedding-keyword` becomes the default: headings are embedded and keyword-indexed, and the reranker keeps seeing the chunk alone.

- Against the baseline, `hybrid-rrf` gains +0.069 MRR@10 [+0.01, +0.15] and `sparse-only` +0.041 [+0.00, +0.09]. `dense-only` gains +0.058 [+0.00, +0.14], which is not a detectable difference on 23 cases.
- Showing headings to the reranker changes one case for the better when nothing else changes (+0.007) and two for the worse when combined with the other switches (−0.028). Neither is detectable. With no evidence for it, it stays off.
- The reranked strategy does not move at all: its ranking was already decided by the cross-encoder, and the candidate pool contained the same evidence before and after.

This is a choice made on 23 cases, with intervals that barely exclude zero or do not. It says which configuration to test, not that the configuration is better. The test-split comparison is published separately once it has been measured.

The switches stay in the code so that this table can be reproduced. Changing `GA_EMBEDDING_CONTEXT` needs a fresh index (`docker compose down -v`, then load again): the chunker version is `markdown/3` with context and `markdown/2` without, and a run refuses a corpus that mixes the two.
