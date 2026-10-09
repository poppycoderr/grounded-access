# Load smoke test — dataset v4

1500 searches from 32 concurrent clients as 5 principals, k=10, commit `7c68772`, generated 2026-10-09T15:06:13+00:00 on `arm`. 9.43 s in total, 159.0 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 500 | 0 | 0 | 0 | 11.9 | 40.5 | 84.9 |
| `dense-only` | 500 | 0 | 0 | 0 | 283.2 | 477.8 | 627.4 |
| `hybrid-rrf` | 500 | 0 | 0 | 0 | 282.9 | 473.4 | 640.2 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
