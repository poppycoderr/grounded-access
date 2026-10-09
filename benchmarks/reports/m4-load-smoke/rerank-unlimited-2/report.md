# Load smoke test — dataset v4

60 searches from 2 concurrent clients as 5 principals, k=10, commit `59df414`, generated 2026-10-09T14:54:46+00:00 on `arm`. 74.44 s in total, 0.8 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 60 | 0 | 7 × rerank_unavailable | 0 | 2534.8 | 3146.0 | 3215.5 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
