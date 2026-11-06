# Load smoke test — dataset v4

600 searches from 16 concurrent clients as 5 principals, k=10, commit `5599870`, generated 2026-11-06T03:17:24+00:00 on `arm`. 63.16 s in total, 9.5 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 200 | 0 | 0 | 0 | 679.5 | 1717.7 | 2465.2 |
| `dense-only` | 200 | 83 × 503 DATABASE_TIMEOUT | 0 | 0 | 2119.4 | 3244.4 | 4260.8 |
| `hybrid-rrf` | 200 | 86 × 503 DATABASE_TIMEOUT | 0 | 0 | 2813.9 | 4494.4 | 5306.8 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
