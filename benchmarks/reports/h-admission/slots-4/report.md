# Load smoke test — dataset v4

400 searches from 16 concurrent clients as 5 principals, k=10, commit `78f0f5f`, generated 2026-10-21T08:53:50+00:00 on `arm`. 4.67 s in total, 85.6 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf` | 400 | 0 | 0 | 0 | 182.9 | 281.5 | 493.5 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
