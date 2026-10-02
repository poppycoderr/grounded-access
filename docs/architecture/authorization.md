# Authorization Model

Status: draft for v0.1. The decision behind this design is in [ADR-0003](../adr/0003-retrieval-time-authorization.md).

## 1. Invariants

These are the properties the design commits to. The table after them says which part is verified today.

**Security invariant.** A chunk row that the principal may not access never leaves the SQL boundary. It never reaches application memory, the reranker, the prompt, logs, traces, metrics, caches or API responses.

**Recall invariant.** Adding the authorization predicate must not make ranking quality on the authorized subset worse than an exact search over that subset would give. v0.1 uses exact vector search, so this holds by construction. Any later approximate index has to demonstrate it holds (ADR-0002).

**Existence invariant.** From outside the system, "you may not see this" and "this does not exist" look the same.

| Invariant | Verified today | Verified by | Gap and milestone |
|---|---|---|---|
| Security | The full decision table | The evaluation security gate: 30 authorization negatives, every returned chunk checked for document and version against hand-labelled visibility, and each principal's full listing compared with its visible set. A property-based test that compares every query path with a separate reference evaluator over random principals and labels; integration tests per rule on all three strategies; an architecture test that only `AuthorizedChunkQuery` reads chunks | Chunk-level labels (v0.2) |
| Recall | By construction | No ANN index exists, so every authorized row is a candidate | A measured comparison of exact search against a filtered HNSW index, once an index exists (post-v0.1, ADR-0002) |
| Existence | For retrieval and document reads | A test reads a document that is confidential, restricted to another department, disabled, deleted, in another tenant, malformed and nonexistent: every response is the same 404, byte for byte apart from the key. A keyword search that only hidden documents could answer returns the same response as one nothing answers. Retrieval returns no filtered counts | A uniform `no_answer` on the answering path (M3). Timing side channels are not mitigated; see the threat model |

## 2. Two kinds of filters

| Kind | Attributes | Who controls it | Counted in |
|---|---|---|---|
| **Authorization** | tenant, classification/clearance, department, project | The verified token and the document labels. A request can never override it. | Security gate (must be zero leakage) |
| **Scope** | document status, validity window, region applicability | Server defaults; the request may pass `asOf` and `region` | Retrieval quality metrics |

Region and validity are scope filters, not authorization. An EU policy isn't secret from a US employee; it just doesn't apply to them. Keeping the two kinds separate means the security metrics only count real access violations. It also allows questions like "what was the travel policy in 2025?" through `asOf`.

How scope is applied ([ADR-0005](../adr/0005-as-of-filters-the-current-version.md)):

- **Validity.** A version is in scope when `valid_from <= asOf < valid_to`; a missing bound is open. `asOf` defaults to the time of the request.
- **Region.** A version with no regions applies everywhere. Otherwise the request's region must be one of them. The region defaults to the principal's `region` claim, and a principal without one gets no region filter.
- **The request may set both.** `asOf` and `region` are parameters of `/retrieval/search` because they are not authorization: asking about another region or another date changes what is relevant, never what the principal may read. The response echoes the scope it used.
- **`asOf` never selects an older version.** Only the current version of a document is a candidate. To answer questions about the past, a superseded policy is published as its own document with a closed validity window.
- **Scope is compiled separately.** It is a second condition next to the authorization predicate, with its own parameters, and the debug chunk listing can drop it (`includeOutOfScope`) without touching the predicate. That listing is what the evaluation compares with the hand-labelled visible set.
- **A change of scope is a change of metadata.** Like a label change, it creates a version that takes over the existing chunks and applies to the next query without embedding again.

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

The policy compiler is a pure function `Principal → SqlPredicate`. The predicate text is the same for every principal; only bound parameters vary, so no principal value can change the shape of the query:

```sql
c.tenant_id = :auth_tenant_id
AND v.classification_rank <= :auth_clearance_rank
AND (cardinality(v.allowed_departments) = 0 OR CAST(:auth_department AS text) = ANY(v.allowed_departments))
AND (cardinality(v.required_projects)  = 0 OR v.required_projects && CAST(:auth_projects AS text[]))
```

- A missing department binds `NULL`, and a comparison with `NULL` is never true. Missing projects bind an empty array, and nothing overlaps an empty array. Both therefore leave only documents that do not restrict that attribute.
- A missing or unknown clearance claim binds the lowest rank. A mistyped claim can never widen access.
- The project list is bound as one array literal with every element quoted, so commas, braces and quotes inside a value stay data.

Every chunk query (sparse, dense and the chunk listing) embeds the **same** predicate object. `policy_version` is `abac/1` and is returned with every result; it is also written to `query_execution` and `audit_event`.

### Labels and versions

Labels belong to a document version and arrive with ingestion (`classification`, `allowedDepartments`, `requiredProjects`). A document without labels is public and unrestricted inside its tenant. A submission is unchanged only if content, format and labels all match the active version.

A submission that changes only the labels creates a new version that takes over the existing chunks, and the pointer flips in the same transaction. It does not chunk or embed, so revoking access does not depend on the model service, and it applies to the next query.

## 4. Identity in v0.1

- Tokens are JWTs signed by a local demo key. `scripts/mint-token <principal>` issues them from `data/principals.yaml`. The control plane only trusts the configured public key.
- Claims: `sub`, `tenant_id`, `department`, `projects`, `clearance`, `region`, `scope`.
- The limitations go in the threat model: there is no real IdP, no revocation, and the demo key is public. Integrating OIDC comes later.

## 5. Existence leakage

- `GET /api/v1/documents/{key}` returns a document's key, title and version number if the principal is authorized for it. It is answered from the same join and the same predicate as search, so a document is readable there exactly when its chunks are retrievable.
- Every other case returns the same 404: a key that does not exist, a document of another tenant, one the principal's attributes do not admit, a disabled or deleted document, and a key that is not well formed. The bodies and headers are identical apart from the key the caller sent.
- Administrative `PATCH` and `DELETE` follow the same rule for keys outside the admin's tenant, and ingestion jobs and execution records for ids the caller does not own.
- Search responses never include "N results were filtered out". A keyword search that only hidden documents could answer is identical to one that nothing answers. A vector search always returns the nearest visible chunks, whatever the query, so its results say nothing about hidden content either.
- Reads are audited as `allow` or `deny`. A `deny` does not record why, so the audit log does not become a list of which hidden documents exist either.
- `status: "no_answer"` will be identical whether nothing was relevant or everything relevant was unauthorized (M3).
- **Known residual risk:** a timing side channel, because an authorized hit, an unauthorized document and a missing one may take different amounts of time. v0.1 does not mitigate it and the threat model states this.

## 6. Caching

v0.1 caches no retrieval results. If caching is added later, the cache key must contain the tenant, a hash of the authorization-relevant attributes and `policy_version`. That work needs its own ADR and its own security tests.

## 7. Testing authorization

- **Unit tests:** compiler output for every row of the decision table, including missing and unknown values, and the quoting of array literals.
- **Property-based tests (jqwik):** generate random principals and labelled documents, including values that would break naive quoting, then compare the result of the listing, the sparse query and the dense query against an in-memory reference evaluator. The reference exists only in test code, so the runtime still has a single implementation. Test rows are written through JDBC arrays, not the production array literal, so a quoting bug cannot cancel itself out.
- **Integration tests (Testcontainers):** both the sparse and the dense query with the real predicate, cross-tenant queries using identical wording, expired and disabled documents, and a label change that creates a new version.
- **Evaluation gate:** humans label each principal's expected visible document set in the eval data. The gate fails if any retrieved candidate or citation falls outside that set. The labels are independent of the compiler (see evaluation strategy).
- **Architecture test:** only `AuthorizedChunkQuery` can query the chunk table.
