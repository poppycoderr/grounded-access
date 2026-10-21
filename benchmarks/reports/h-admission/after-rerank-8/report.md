# Load smoke test — dataset v4

60 searches from 8 concurrent clients as 5 principals, k=10, commit `78f0f5f`, generated 2026-10-21T08:59:37+00:00 on `arm`. 17.3 s in total, 3.5 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 60 | 0 | 43 × rerank_unavailable | 0 | 2010.7 | 2958.0 | 3395.9 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
