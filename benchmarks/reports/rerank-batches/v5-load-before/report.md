# Load smoke test — dataset v5

150 searches from 1 concurrent clients as 5 principals, k=10, commit `cac172a`, generated 2026-11-12T14:41:34+00:00 on `arm`. 365.36 s in total, 0.4 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 150 | 0 | 78 × rerank_unavailable | 0 | 3033.5 | 3110.4 | 3184.3 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
