# Load smoke test — dataset v4

60 searches from 1 concurrent clients as 5 principals, k=10, commit `59df414`, generated 2026-10-09T14:53:31+00:00 on `arm`. 63.66 s in total, 0.9 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 60 | 0 | 0 | 0 | 1053.8 | 1487.3 | 1632.3 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
