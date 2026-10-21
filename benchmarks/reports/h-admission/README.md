# Deadlines and admission under load — dataset v4

Local runs on an Apple M1 Max (10 cores, `arm` in the run files) with each stack in Docker and the client on the same machine, 2026-10-21. The machine ran other work (load average 6 to 10), so differences of a few tens of milliseconds are within noise. Every run started from an empty database loaded with the demo corpus; [`scenarios.sh`](./scenarios.sh) is the exact sequence.

- **Before** is `main` at `78f0f5f`, without ADR-0010.
- **After** is the same commit plus the change in this pull request, with eight query slots.

The run files record the evaluator's checkout, which is `78f0f5f` for both.

Every result of the 4,120 searches below was checked against the visible set of the principal that asked: 0 unauthorized results, every response `200`.

## Searches, with and without ingestion

400 `hybrid-rrf` searches from 16 concurrent clients, three times:
- alone;
- while [`flood.py`](./flood.py) ingested synthetic documents into a separate tenant for 90 s (about 480 words each, in jobs of 20; 400 documents in each run);
- after the ingestion stopped.

| Run | p50 ms | p95 ms | max ms | Degraded |
|---|---|---|---|---|
| [before, alone](./before-queries/report.md) | 162 | 254 | 347 | 0 |
| [after, alone](./after-queries/report.md) | 174 | 260 | 360 | 0 |
| [before, during ingestion](./before-queries-during-ingestion/report.md) | 165 | 249 | 313 | 0 |
| [after, during ingestion](./after-queries-during-ingestion/report.md) | 179 | 278 | 407 | 0 |
| [before, after ingestion](./before-queries-after/report.md) | 146 | 214 | 250 | 0 |
| [after, after ingestion](./after-queries-after/report.md) | 158 | 242 | 336 | 0 |

- **No measurable difference.** The two versions do not differ beyond the noise of this machine.
- **Ingestion did not slow searches in either version.** The ingestion worker embeds one document at a time, so it never competed for more than one computation. This run therefore does not show what the separate passage lane is for: several workers or control-plane replicas ingesting at once. The model-service tests show that case (`test_a_passage_flood_neither_delays_nor_refuses_queries`).

## How many query embeddings may run at once

Same 400 searches, one stack, the model service restarted with each setting of `GA_QUERY_SLOTS`:

| Query slots | p50 ms | p95 ms | max ms |
|---|---|---|---|
| [2](./slots-2/report.md) | 248 | 408 | 517 |
| [4](./slots-4/report.md) | 183 | 282 | 494 |
| [8](./slots-8/report.md) | 153 | 225 | 324 |
| [16](./slots-16/report.md) | 139 | 218 | 272 |

- **The first default was wrong.** The first version of this change used two slots and made searches about 50% slower than before at the median. ONNX Runtime on this CPU gets more throughput from several concurrent query embeddings.
- **Eight is the default.** It matches the speed without a limit. Sixteen would be slightly faster, but slots and queues of all lanes would then exceed the 40 threads FastAPI runs requests on, and a waiting request could hold up the health check.

## Reranking beyond its capacity

60 `hybrid-rrf-rerank` searches from 8 concurrent clients. The cross-encoder scores about one query per second here.

| Run | p50 ms | p95 ms | max ms | Reranked | `rerank_unavailable` |
|---|---|---|---|---|---|
| [before](./before-rerank-8/report.md) | 3,109 | 3,909 | 4,224 | 28 | 32 |
| [after](./after-rerank-8/report.md) | 2,011 | 2,958 | 3,396 | 17 | 43 |

- **The trade-off.** With a 5 s deadline, a search waits for a reranking slot only as long as a whole 3 s rerank call still fits. Waits are therefore shorter: the slowest search is 0.8 s faster, and fewer searches get reranked.
- **The bound.** Every search ended within the deadline plus database time. Before, the wait for a slot and the call each had their own 3 s.
- **Not shown.** These runs do not exercise model-service refusals (`503 BUSY`): with one control plane, its own rerank semaphore keeps at most one call in flight. The integration tests do (`DependencyFailureIT`, `SearchDeadlineIT`).
