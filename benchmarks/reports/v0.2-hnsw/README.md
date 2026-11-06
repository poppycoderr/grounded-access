# Exact search and HNSW under the authorization filter

This is the measurement ADR-0002 deferred: what an HNSW index does to dense search when the authorization predicate filters most rows.

**What was run.**
- **The corpus.** 33,283 chunks: the labelled corpus plus 4,200 generated distractors (`ga-eval distractors --seed 1`), the largest size of
  the [scale run](../v0.2-scale/README.md).
- **The searches.** [`scripts/hnsw-run`](../../../scripts/hnsw-run) sends every evaluation query as each of five principals through the real
  dense path: the API, the compiled predicate, and the same SQL statement. That is 640 searches per configuration.
- **What changes between configurations.** Only the index and database settings, followed by a control-plane restart.
- **The machine.** An Apple M3 laptop, Docker with 6 CPUs.
- **The software.** The server at `415c1ab`, PostgreSQL 17.11 and pgvector 0.8.7 (`environment.csv`).

Every result was checked against the principal's visible set. There was no unauthorized result in any configuration.

**The measures.**
- **Recall@10** is the share of exact search's top 10 that a configuration also returned.
- **Distance recall** is the share of its top 10 that is at least as close as exact search's 10th result. It was added to rule out
  near-ties, and it equals recall in every configuration, so the misses are real misses.
- **Index use** is read from `pg_stat_user_indexes.idx_scan`.
- Every number below comes from the `ann-recall.md`, `index-use.csv` and `selectivity.csv` files in this directory.

| Principal | Visible chunks | Share |
|---|---|---|
| carol-manager | 24,170 | 72.6% |
| alice-engineer | 19,766 | 59.4% |
| bob-support | 19,408 | 58.3% |
| dave-contractor | 3,611 | 10.8% |
| mallory-outsider | 390 | 1.2% |

`mallory-outsider` (external tenant, public clearance) is the selective case: 1.2% of the chunks, close to the 2% ADR-0002 named.

## Finding 1: with the index available, PostgreSQL does not use it

[`hnsw-planner`](./hnsw-planner/ann-recall.md) creates the index (m = 16, ef_construction = 64, 68 MB, built in 6 s) and changes nothing
else.

- **The index is never used.** All 640 searches used 0 index scans, and recall is 1.000.
- **Latency is unchanged.** p50 is 69.9 ms, against 66.8 ms for exact search.
- **Why.** For the dense statement with its joins and predicate, the planner estimates that filtering and then sorting the filtered rows
  costs less than walking the graph and filtering afterwards. That holds even for the broad principals, at this corpus size.

An index added at this size costs storage and write time and changes nothing.

## Finding 2: forced, the index loses most of the true neighbours

`enable_sort = off` leaves the ordered index scan as the only plan. The index returns its nearest rows, and the predicate filters them
afterwards: the post-filtering ADR-0002 warns about. This is a measuring device for what the planner would do once the index is cheaper,
not a configuration.

| Configuration | Index scans | Recall, broad principals | dave (10.8%) | mallory (1.2%) | Searches with fewer results | p50 ms |
|---|---|---|---|---|---|---|
| [exact](./exact/) | – | 1.000 | 1.000 | 1.000 | 0 | 66.8 |
| [forced, `ef_search` 40](./hnsw-forced/ann-recall.md) | 615 | 0.182–0.191 | 0.239 | 0.048 | 255, 85 of them empty | 26.2 |
| [forced, iterative, strict order](./hnsw-forced-iterative-strict/ann-recall.md) | 629 | 0.182–0.191 | 0.338 | 0.432 | 0 | 39.9 |
| [forced, iterative, relaxed order](./hnsw-forced-iterative-relaxed/ann-recall.md) | 636 | 0.209–0.244 | 0.622 | 0.863 | 0 | 35.6 |
| [forced, partial index on the external tenant](./hnsw-forced-partial-external/ann-recall.md) | 117 | 1.000 | 1.000 | 0.420 | 122 | 159.9 |

**Two separate effects show here.**

**Post-filtering, as ADR-0002 expected.**
- **Without iterative scans, selective principals get almost nothing.** The index returns at most `ef_search` (40) rows, and the predicate
  removes most of them. For `mallory-outsider` 82 of 128 searches came back empty and every one came back short; recall was 0.048.
- **Iterative scans repair the counts.** pgvector 0.8 continues the graph scan until enough rows pass the filter: no search came back
  short. Relaxed order brought `mallory-outsider` to 0.863.
- **A partial index is no help here.** It holds only the external tenant's chunks, so `mallory-outsider`'s plan uses it. But public
  clearance still removes most of those rows afterwards: recall 0.420, 122 searches short.

**Unreachable neighbours, which this corpus causes.**
- **The broad principals stay near 0.2 in every forced configuration**, although they may read about 60% of the chunks. So this loss is
  not caused by the filter.
- **A direct check found the cause.** For the first case's query vector, the true nearest chunk is a labelled `hr-volunteer-policy` chunk
  at score 0.877. The index scan without any filter returns distractors around 0.586 instead, at `ef_search` 40, 200 and 1,000 alike.
- **What that means.** About 99.6% of the vectors are one dense cluster of generated text, and the 135 labelled chunks lie outside it. The
  graph search stays inside the cluster and does not reach them.

That is a property of this synthetic corpus as much as of HNSW. A corpus of written documents, which step 1a of the plan adds, would not
have one dominating cluster.

## What follows

- **No approximate index for v0.2.** At 33,283 chunks exact search takes 67 ms, the planner never chooses the index, and a forced index
  breaks the recall invariant of the [authorization model](../../../docs/architecture/authorization.md): ranking on the authorized subset
  must not be worse than exact search over it.
- **The precondition stays as it is.** Any later approximate index has to pass this comparison, with iterative scans and on a written
  corpus, before it is considered. `scripts/hnsw-run` and `ga-eval ann-recall` make that a repeatable check.
- **A second backend is not supported by this either.** Nothing here shows a limit of PostgreSQL that another store would remove.

## What this does not show

- **One corpus, one machine.** Recall on a corpus without one dominating cluster is not measured here.
- **Default build parameters only.** Larger `m` or `ef_construction` and larger `ef_search` with iterative scans were not tried, apart
  from the direct check above. The build ran past `maintenance_work_mem` (PostgreSQL said so) and took 6 s.
- **Dense-only searches, sequential.** No hybrid search, no concurrency. Latency under the forced index is reported, but it is not a case
  for using the index, because its results are wrong.

## Reproduce

```text
COMPOSE_PROJECT_NAME=ga-scale ./scripts/scale-run                 # or load the corpus and 4,200 distractors yourself
DISTRACTORS=results/scale-<timestamp>/size-4200/distractors COMPOSE_PROJECT_NAME=ga-scale ./scripts/hnsw-run
```

The script creates and drops indexes and changes database settings. Run it only against a throwaway database.
