# Load smoke test — dataset v4

300 searches from 1 concurrent clients as 5 principals, k=10, commit `5599870`, generated 2026-11-06T02:50:25+00:00 on `arm`. 4.96 s in total, 60.5 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 100 | 0 | 0 | 0 | 4.9 | 7.4 | 45.4 |
| `dense-only` | 100 | 0 | 0 | 0 | 21.3 | 27.5 | 46.1 |
| `hybrid-rrf` | 100 | 0 | 0 | 0 | 22.5 | 29.1 | 33.9 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
