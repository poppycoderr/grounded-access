# Load smoke test — dataset v4

300 searches from 1 concurrent clients as 5 principals, k=10, commit `5599870`, generated 2026-11-06T03:16:18+00:00 on `arm`. 162.49 s in total, 1.8 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 100 | 0 | 0 | 0 | 122.3 | 293.7 | 409.6 |
| `dense-only` | 100 | 0 | 0 | 0 | 793.9 | 981.7 | 990.3 |
| `hybrid-rrf` | 100 | 0 | 0 | 0 | 918.2 | 1228.8 | 1375.8 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
