# Load smoke test — dataset v4

60 searches from 4 concurrent clients as 5 principals, k=10, commit `7c68772`, generated 2026-10-09T15:05:36+00:00 on `arm`. 51.61 s in total, 1.2 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 60 | 0 | 6 × rerank_unavailable | 0 | 3355.3 | 4044.6 | 4290.6 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
