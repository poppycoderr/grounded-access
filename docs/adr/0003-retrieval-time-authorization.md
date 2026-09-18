# ADR-0003: Enforce authorization at candidate retrieval time

- Status: Proposed
- Date: 2026-09-18

## Context

Many RAG systems retrieve top-k first and filter unauthorized results in the application afterwards. That approach has two problems:

1. **Leakage surface.** Unauthorized text reaches application memory, and from there it can reach rerankers, prompts, logs, traces and caches.
2. **Recall loss.** When most of the top-k is unauthorized, the principal gets few results or none, even though relevant authorized chunks exist further down the ranking.

## Decision

- A pure policy compiler turns the verified principal into one parameterized SQL predicate. The sparse and dense queries both embed that same predicate object.
- Only `AuthorizedChunkQuery` may query the `chunk` table, and an architecture test enforces this.
- Downstream stages accept only `AuthorizedCandidate`, a type that only the retrieval package can construct.
- The rules are those in the decision table in [authorization.md](../architecture/authorization.md). Authorization filters and scope filters are kept apart.
- Policies are code with versioned tests. There is no external policy engine in v0.1.
- A document that is not visible looks the same as one that does not exist: 404, `no_answer`, no filtered counts.

## Consequences

- One implementation of the rules, used by every retrieval path.
- SQL shape and indexing must respect the predicate. Rules that cannot be expressed in SQL (for example ones that need external lookups) are out of scope unless attributes are first materialized into labels.
- Label changes go through document versioning, so policy changes are auditable.

## Alternatives considered

- **Post-filtering in the application:** rejected for the two reasons above.
- **OPA or Cedar:** worth considering once rules need to be shared across services or governed by non-developers. Partial evaluation to SQL would be needed, and that adds complexity with no v0.1 benefit.
- **One index per tenant or role:** makes combinations of labels explode, and it still needs a filter inside each tenant.
