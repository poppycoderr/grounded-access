# Load smoke test — dataset v4

Local runs of `./scripts/load-smoke` on an Apple M1 Max (10 cores) with the stack in Docker and the client on the same machine, 2026-10-09. The machine was running other work at the time (load average 11 to 25), so read the numbers as orders of magnitude. Each row is one run; its `run.json` and generated report are in the directory named in the first column. The corpus has 35 documents, so this says nothing about retrieval at scale.

Every result of the 1,980 requests in these runs was checked against the visible set of the principal that asked: 0 unauthorized results, and no request was refused.

## More clients than database connections

1,500 searches from 32 concurrent clients as 5 principals. The connection pool has 10 connections.

| Run | Strategy | Requests | Not 200 | Degraded | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `no-rerank-32` | `sparse-only` | 500 | 0 | 0 | 11.9 | 40.5 | 84.9 |
| `no-rerank-32` | `dense-only` | 500 | 0 | 0 | 283.2 | 477.8 | 627.4 |
| `no-rerank-32` | `hybrid-rrf` | 500 | 0 | 0 | 282.9 | 473.4 | 640.2 |

159 requests per second in total. A single dense request takes about 25 ms on this machine; under 32 clients it takes about 280 ms, and the keyword channel stays at 12 ms. The time goes to the queue in front of the embedding model, not to PostgreSQL.

## Where reranking saturates

60 `hybrid-rrf-rerank` searches per run. The cross-encoder scores 20 passages in about one second on this CPU, so the stack reranks about one query per second however many clients ask.

Before the change in this report, at commit `59df414`, every caller sent its rerank call at once:

| Run | Clients | Reranked | Degraded | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|
| `rerank-unlimited-1` | 1 | 60 | 0 | 1,054 | 1,487 | 1,632 |
| `rerank-unlimited-2` | 2 | 53 | 7 | 2,535 | 3,146 | 3,216 |
| `rerank-unlimited-4` | 4 | 0 | 60 | 3,233 | 3,334 | 3,415 |
| `rerank-unlimited-8` | 8 | 0 | 60 | 6,628 | 9,174 | 13,300 |

With four clients no query was reranked at all: four overlapping calls each took longer than the 3 s timeout. With eight, requests took up to 13 s although the rerank timeout is 3 s. A timeout ends the wait, not the work: the model service kept scoring passages for callers that had given up, and the embedding call of every later query queued behind that work.

At commit `7c68772` one rerank call is in flight at a time. A query waits up to the rerank timeout for the slot, and if it does not get one it is answered in the fused order without being sent to the reranker:

| Run | Clients | Reranked | Degraded | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|
| `rerank-limited-1` | 1 | 60 | 0 | 976 | 1,203 | 1,438 |
| `rerank-limited-2` | 2 | 60 | 0 | 1,932 | 2,256 | 2,329 |
| `rerank-limited-4` | 4 | 54 | 6 | 3,355 | 4,045 | 4,291 |
| `rerank-limited-8` | 8 | 24 | 36 | 3,110 | 4,196 | 4,223 |

Four clients now get 54 of 60 queries reranked instead of none, and the slowest request with eight clients takes 4.2 s instead of 13.3 s.

## What this does not fix

- A request can still take about 4 s: up to 3 s waiting for the slot, then the call. The limit bounds the damage; it does not make reranking fast.
- Throughput of reranked answers stays at about one per second on a CPU. Beyond that, the system answers in the fused order and says so (`degraded: rerank_unavailable`), which on this dataset costs ranking quality, not recall (see the [retrieval report](../m3-retrieval/report.md)).
- The embedding model is the next queue: dense latency grows tenfold under 32 clients. Nothing limits or sheds that load yet.
- There is no rate limiting per principal, so one caller can use the whole rerank capacity.
