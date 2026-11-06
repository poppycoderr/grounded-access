# Load smoke test — dataset v4

600 searches from 16 concurrent clients as 5 principals, k=10, commit `5599870`, generated 2026-11-06T02:50:30+00:00 on `arm`. 3.87 s in total, 155.1 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 200 | 0 | 0 | 0 | 11.8 | 36.0 | 79.3 |
| `dense-only` | 200 | 0 | 0 | 0 | 136.0 | 204.1 | 261.5 |
| `hybrid-rrf` | 200 | 0 | 0 | 0 | 144.3 | 223.5 | 245.9 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
