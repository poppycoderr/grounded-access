# Load smoke test — dataset v5

150 searches from 1 concurrent clients as 5 principals, k=10, commit `cac172a`, generated 2026-11-12T14:54:06+00:00 on `arm`. 164.65 s in total, 0.9 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 150 | 0 | 0 | 0 | 1043.5 | 2036.5 | 2715.8 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
