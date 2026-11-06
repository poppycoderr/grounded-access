# Load smoke test — dataset v4

600 searches from 16 concurrent clients as 5 principals, k=10, commit `718f16f`, generated 2026-11-06T14:38:55+00:00 on `arm`. 20.41 s in total, 29.4 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 200 | 0 | 0 | 0 | 417.8 | 1092.0 | 1609.4 |
| `dense-only` | 200 | 0 | 0 | 0 | 356.5 | 627.2 | 1088.7 |
| `hybrid-rrf` | 200 | 0 | 0 | 0 | 767.0 | 1456.4 | 1782.7 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
