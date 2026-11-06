# Load smoke test — dataset v4

600 searches from 16 concurrent clients as 5 principals, k=10, commit `5599870`, generated 2026-11-06T02:52:41+00:00 on `arm`. 11.94 s in total, 50.3 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 200 | 0 | 0 | 0 | 108.1 | 326.7 | 499.1 |
| `dense-only` | 200 | 0 | 0 | 0 | 363.4 | 623.1 | 984.7 |
| `hybrid-rrf` | 200 | 0 | 0 | 0 | 507.9 | 831.1 | 1001.0 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
