# Load smoke test — dataset v4

128 searches from 1 concurrent clients as 1 principals, k=10, commit `415c1ab`, generated 2026-11-06T15:34:06+00:00 on `arm`. 9.96 s in total, 12.8 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `dense-only` | 128 | 0 | 0 | 0 | 68.6 | 103.0 | 530.9 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
