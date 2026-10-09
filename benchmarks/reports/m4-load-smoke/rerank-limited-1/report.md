# Load smoke test — dataset v4

60 searches from 1 concurrent clients as 5 principals, k=10, commit `7c68772`, generated 2026-10-09T15:03:45+00:00 on `arm`. 57.19 s in total, 1.0 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 60 | 0 | 0 | 0 | 975.5 | 1202.7 | 1437.8 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
