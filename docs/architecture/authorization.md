# Authorization Model

Status: draft for v0.1. The decision behind this design is in [ADR-0003](../adr/0003-retrieval-time-authorization.md).

## 1. Invariants

These are the properties the design commits to. The table after them says which part is verified today, because M0 enforces tenant isolation only.

**Security invariant.** A chunk row that the principal may not access never leaves the SQL boundary. It never reaches application memory, the reranker, the prompt, logs, traces, metrics, caches or API responses.

**Recall invariant.** Adding the authorization predicate must not make ranking quality on the authorized subset worse than an exact search over that subset would give. v0.1 uses exact vector search, so this holds by construction. Any later approximate index has to demonstrate it holds (ADR-0002).

**Existence invariant.** From outside the system, "you may not see this" and "this does not exist" look the same.

| Invariant | Verified today | Verified by | Gap and milestone |
|---|---|---|---|
| Security | Tenant level | `RetrievalIT` cross-tenant cases on both channels; an architecture test that only `AuthorizedChunkQuery` reads chunks; the evaluation security gate against hand-labelled visibility | Label-level decisions and a gate at version and chunk granularity (M2) |
| Recall | By construction | No ANN index exists, so every authorized row is a candidate | A measured comparison of exact search against a filtered HNSW index, once an index exists (post-v0.1, ADR-0002) |
| Existence | Partially | Retrieval returns no filtered counts and no metadata for rows the predicate excluded | A document read endpoint returning 404, an answering path with a uniform `no_answer`, and timing side channels (M2–M3, threat model) |

## 2. Two kinds of filters

| Kind | Attributes | Who controls it | Counted in |
|---|---|---|---|
| **Authorization** | tenant, classification/clearance, department, project | The verified token and the document labels. A request can never override it. | Security gate (must be zero leakage) |
| **Scope** | document status, validity window, region applicability | Server defaults; the request may pass `asOf` and `region` | Retrieval quality metrics |

Region and validity are scope filters, not authorization. An EU policy isn't secret from a US employee; it just doesn't apply to them. Keeping the two kinds separate means the security metrics only count real access violations. It also allows questions like "what was the travel policy in 2025?" through `asOf`.

Default scope: `status = active`, `valid_from <= now() < coalesce(valid_to, 'infinity')`, and `region` taken from the principal's region, with an empty `applies_to_regions` matching every region.

## 3. Decision table (v0.1)

A chunk is authorized for a principal **if and only if every row below holds** (the rows are ANDed).

| # | Attribute | Principal side | Document side | Rule | Missing principal value |
|---|---|---|---|---|---|
| 1 | Tenant | `tenant_id` (required claim) | `chunk.tenant_id` | equal | token rejected |
| 2 | Clearance | `clearance` | `classification` | `rank(classification) <= rank(clearance)`, order `public < internal < confidential < restricted` | treated as `public` |
| 3 | Department | `department` | `allowed_departments` | array empty, **or** department ∈ array | only unrestricted documents |
| 4 | Project | `projects[]` | `required_projects` | array empty, **or** intersection non-empty (any-of) | only unrestricted documents |

The default is deny. Anything not explicitly allowed by the rules above is invisible.

Deferred to v0.2, each with its own ADR: `groups`, `roles`, explicit deny labels, and chunk-level labels. If chunk-level labels are added, they may only **narrow** document access and never widen it. The policy compiler will enforce this by ANDing chunk rules onto document rules.

### Compiled form

The policy compiler is a pure function `(Principal, PolicyVersion) → SqlPredicate`, and its output is bound parameters only:

```sql
c.tenant_id = :tenant
AND v.classification_rank <= :clearance_rank
AND (cardinality(v.allowed_departments) = 0 OR :department = ANY(v.allowed_departments))
AND (cardinality(v.required_projects)  = 0 OR v.required_projects && :projects::text[])
```

The sparse and dense queries embed the **same** predicate object. `policy_version` is the compiler version plus the label schema version. It is written to `query_execution` and `audit_event`.

## 4. Identity in v0.1

- Tokens are JWTs signed by a local demo key. `scripts/mint-token <principal>` issues them from `data/principals.yaml`. The control plane only trusts the configured public key.
- Claims: `sub`, `tenant_id`, `department`, `projects`, `clearance`, `region`, `scope`.
- The limitations go in the threat model: there is no real IdP, no revocation, and the demo key is public. Integrating OIDC comes later.

## 5. Existence leakage

- A document that isn't visible returns 404, the same as a document that doesn't exist. Admin `DELETE` follows the same rule when the document is outside the admin's tenant.
- Responses and traces never include "N results were filtered out".
- `status: "no_answer"` is identical whether nothing was relevant or everything relevant was unauthorized.
- **Known residual risk:** a timing side channel, because authorized and unauthorized misses may take different amounts of time. v0.1 does not mitigate it and the threat model states this.

## 6. Caching

v0.1 caches no retrieval results. If caching is added later, the cache key must contain the tenant, a hash of the authorization-relevant attributes and `policy_version`. That work needs its own ADR and its own security tests.

## 7. Testing authorization

- **Unit tests:** compiler output for every row of the decision table, including missing values.
- **Property-based tests (jqwik):** generate random principals and labelled documents, then compare the SQL result against an in-memory reference evaluator. The reference exists only in test code, so the runtime still has a single implementation.
- **Integration tests (Testcontainers):** both the sparse and the dense query with the real predicate, cross-tenant queries using identical wording, expired and disabled documents, and a label change that creates a new version.
- **Evaluation gate:** humans label each principal's expected visible document set in the eval data. The gate fails if any retrieved candidate or citation falls outside that set. The labels are independent of the compiler (see evaluation strategy).
- **Architecture test:** only `AuthorizedChunkQuery` can query the chunk table.
