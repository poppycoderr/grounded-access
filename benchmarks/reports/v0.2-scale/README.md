# Scale run — labelled corpus plus generated distractors

The first scale run of the [v0.2 plan](../../../docs/project/v0.2-plan.md) (step 1c). The stack was measured three times, each time on a
larger corpus.

**What was run.**
- **The machine.** An Apple M3 laptop (8 cores, `arm` in the run files). The stack ran in Docker with 6 CPUs and 12 GB, and the client ran
  on the same machine. The machine was also running other work: load averages of 3 to 7 were recorded at the start and end of the runs
  (`environment-*.csv`).
- **The sequence.** [`scripts/scale-run`](../../../scripts/scale-run) on a stack started from an empty database and loaded with
  `./scripts/load-demo`.
- **The corpus.** At each size the corpus grows by documents from `ga-eval distractors --seed 1`. The generator is sequential, so each
  size contains the smaller ones. The distractor directories are not committed; the seed and the document count reproduce them.

**What was checked.** Every result was checked against the visible set of the principal that asked: the hand-labelled set for the corpus,
and the generator's `visibility.yaml` for distractors. There was no unauthorized result in either run.

| Size | Distractor documents | Chunks in the database |
|---|---|---|
| labelled corpus only | 0 | 135 |
| about 25× | 400 | 3,263 |
| about 250× | 4,200 | 33,283 |

The labelled corpus is 135 chunk rows. Earlier reports quote 328 chunks "as listed": that is the sum over the evaluation principals'
listings, so a chunk visible to three principals counts three times.

Two runs are kept:

- [`before-fix/`](./before-fix/summary.md) is the server at `5599870`, the first run. It found the dense-search slowdown below.
- [`after-fix/`](./after-fix/summary.md) is the server at `1f00a8c`, which fixes it (poppycoderr/grounded-access#86), measured from an empty
  database again. Its largest size was measured twice: the first attempt stopped when all three containers were shut down from outside at
  14:35 UTC (PostgreSQL logged an orderly shutdown, with no OOM kill). That attempt is not kept.

Each `summary.md` is rendered by `ga-eval scale-report` from the run files next to it. Every number below is copied from those summaries.

## Finding 1: dense search slowed down about thirteenfold on every pooled connection

In the first run, dense search at 33,283 chunks took about 800 ms sequentially. A single statement run by hand took 60 ms. With 16
clients, 169 of 400 dense and hybrid searches exceeded the 2 s retrieval SQL limit and were refused with `503 DATABASE_TIMEOUT`.

The latencies of 60 consecutive dense searches on one fresh connection show where it starts
([before](./before-fix/dense-sequence-33283-chunks.jsonl), [after](./after-fix/dense-sequence-33283-chunks.jsonl)):

```text
before: 193 104 74 86 88 73 72 72 73 | 827 814 996 43 42 813 806 800 994 815 ...   ms
after : 234  80 72 86 84 94 69 72 74 |  69  70  82 26 29  70  68  72  83  70 ...
```

**The cause.**
- After five executions the JDBC driver prepares a statement on the server, and PostgreSQL may then reuse a generic plan.
- The query vector was bound as text and cast inline. In the generic plan that cast moved into the sort key and ran for every compared
  row, about 20,000 times per search.
- The fix casts the vector once, in a subquery. `DenseQueryPlanIT` checks it with both plan modes.

| 33,283 chunks | Before: p50 / p95 ms | After: p50 / p95 ms | Not 200, before → after |
|---|---|---|---|
| `dense-only`, 1 client | 793.9 / 981.7 | 68.7 / 87.4 | 0 → 0 |
| `dense-only`, 16 clients | 2119.4 / 3244.4 | 356.5 / 627.2 | 83 → 0 |
| `hybrid-rrf`, 16 clients | 2813.9 / 4494.4 | 767.0 / 1456.4 | 86 → 0 |

**Before 33,283 chunks.** At the smaller sizes the slowdown was present but small, because few rows were compared. At 3,263 chunks the
dense p95 was 113 ms before and 34 ms after, sequentially.

## Finding 2: after the fix, the keyword channel grows fastest

From [after-fix](./after-fix/summary.md), the stage timings as the execution records store them, p50 / p95 in ms:

| Chunks | Sparse SQL, 1 client | Dense stage, 1 client | Sparse SQL, 16 clients | Dense stage, 16 clients |
|---|---|---|---|---|
| 135 | 1 / 1 | 12 / 18 | 1 / 5 | 90 / 140.05 |
| 3,263 | 8 / 25.15 | 18 / 26 | 36 / 147.35 | 113.5 / 203.55 |
| 33,283 | 109 / 282.55 | 64 / 82.05 | 384.5 / 1008.7 | 330.5 / 566.5 |

The dense stage includes the query embedding. That is most of it at the smallest size, and it does not depend on the corpus.

**What the table shows.**
- **Sparse search grows fastest.** From 135 to 33,283 chunks the sparse statement grows about a hundredfold, and at the largest size it is
  slower than dense search. Ranking every matching row with `ts_rank_cd` costs more as more rows match.
- **The distractors make this an upper estimate.** Their text is generated from small vocabularies, so a common query word matches far
  more of them than it would in written documents. The measured growth is real for this text, and probably higher than for real text.
  Which it is for real text needs the corpus of step 1a.
- **Exact dense search is not the bottleneck at this size.** A sequential dense stage of 64 ms on 33,283 chunks, embedding included,
  leaves little for an approximate index to win. Its filtered recall has not been measured.
- **Under 16 clients both channels wait.** Each grows to several hundred ms. The run records no refusal and no degraded result, so this is
  queueing for CPU on a 6-CPU container shared by the database, the model service and the control plane, not a limit being hit.

## Audit growth

Every search writes one execution record and one audit event: 2,000 rows per 1,000 searches in every run. The size each 1,000 searches
add to both tables, indexes included, ranged from 800 to 1,573 KiB in the after-fix run and from 1,053 to 1,760 KiB before. Page reuse
and autovacuum make a single reading noisy, so the order of magnitude is the result: about 1.5 MiB per 1,000 searches, or about 1.5 GiB
per million. That is what a retention under ADR-0013 bounds.

The 16-client run at 33,283 chunks before the fix records fewer rows (1,437 per 1,000 searches). It counts every request, and the 169
refused ones wrote neither an execution record nor an audit event: 431 answered searches wrote 862 rows.

## What this does not show

- **One machine.** Another machine, other Docker resources, or a quieter host give other absolute numbers. The before and after runs were
  made on the same machine within a day. The before run's files record no host load. Checked by hand while it ran, the load average was
  about 6 to 7, close to the 3 to 7 recorded for the after run.
- **Synthetic text.** It inflates the keyword channel's match counts (see finding 2). The labelled cases are the same 128 as always. No
  case asks about distractor content, so these runs measure cost, not quality.
- **No approximate index.** The selective-filter comparison of exact search with HNSW (ADR-0002) is a separate run.
- **No connection-pool exhaustion.** No request was refused for a connection (`DATABASE_UNAVAILABLE`), so the pool of 10 was not the
  limit at 16 clients.
- **No reranking.** The reranking strategy is CPU-bound in the model service, about one query per second
  ([load smoke](../m4-load-smoke/report.md)). That does not depend on the corpus size, because it always scores 20 candidates.

## Reproduce

```text
docker compose -p ga-scale down -v && docker compose -p ga-scale up -d --build --wait
COMPOSE_PROJECT_NAME=ga-scale ./scripts/load-demo
COMPOSE_PROJECT_NAME=ga-scale ./scripts/scale-run          # SIZES="0 400 4200" by default
uv run --project packages/evaluation ga-eval scale-report results/scale-<timestamp>
```
