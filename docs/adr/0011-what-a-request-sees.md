# ADR-0011: What a request sees, and when a revocation reaches it

- Status: Accepted
- Date: 2026-10-21

## Context

The authorization predicate is applied in SQL, and a label change, a disable or a delete applies to the next query (threat model, A8). The
2026-10-21 reviews found four things that this does not settle:

- **D1: one search, several snapshots.** A search runs its sparse and dense channels as two separate statements, without a shared
  transaction. A revocation committed between them is seen by the second statement and not by the first.
- **D2: answer generation.** An answer request retrieves its evidence and then waits for the chat model, up to its read timeout. A revocation
  committed meanwhile is not checked again, and evidence already sent to the chat model cannot be recalled.
- **Citations.** A response cites `key@version`. A label or link change (ADR-0006) moves the chunks to a new version in the same
  transaction, so the old version is unreadable at once, not only after cleanup. The API has no endpoint that reads an earlier version.
- **D6: one profile per database.** Each process checked the index profile only for itself, so two processes with different configurations
  could write two profiles into one database. ADR-0007 assumed a single configuration without enforcing it.

## Decision

### Authorization applies per statement

- **Per statement.** Every retrieval statement applies the authorization and scope predicates as of its own start. Each row a response
  returns was readable by the caller when the statement that returned it ran.
- **No single snapshot.** A search does not read one snapshot. If a revocation commits between its channels, the result can contain rows of
  the earlier statement. A single read snapshot would hide revocations committed while the search runs, not prevent them. It would also need
  a read transaction across the channels, which must not span the model call in between. It is not adopted now.

### A request already running completes

- **Running requests are not re-checked.** A revocation reaches every request whose retrieval statements start after it commits. Requests
  already past retrieval complete with what they retrieved.
- **Evidence already sent to the chat model stays sent.** The answer may contain it. A last check before responding would narrow the window
  but not close it: generation has already seen the text, and a statement written from revoked evidence would have to be dropped as a whole.
  A stronger guarantee needs a separate design: a permission epoch, cancellation of model calls, and a rule for answers built from revoked
  evidence. It is not part of this release.

### Evidence is a snapshot; citations are not resolvable later

- **The response carries the evidence.** It returns the evidence text it used. `key@version` names what was read at that moment.
- **No historical reads.** The service does not resolve earlier versions. `GET /api/v1/documents/{key}` reads the current version only, and a
  label or link change makes the previous version unreadable immediately. A client that needs to show a citation later keeps the evidence
  from the response.
- **Not planned.** Historical citation resolution would need retained text, stable locators, and authorization against the labels in force
  when it is read. None of that is planned.

### One database, one writing profile

- **The anchor.** A database is anchored to one index profile (migration V13). The first version written claims it, in its write
  transaction. The claim is an atomic insert, so two first writers cannot both succeed. Every later version written with another profile is
  refused (`INDEX_PROFILE_MISMATCH`), including one from a process that started while the database was empty. The startup check compares
  the anchor too.
- **What stays possible.** Several control-plane processes with the same configuration may write. A label change writes no vectors and is
  never refused, so labels can be narrowed with the model service down.
- **Changing the profile.** Emptying the database does not release the anchor: a different profile means a new database (see the upgrade
  notes).

### Audit rows are kept

- **No retention.** `audit_event` and `query_execution` have no retention period. Nothing deletes them, and both grow with use: one execution
  record and one audit event per search, and one more event per answer. Choosing a retention period, an export and an alert on storage is an
  operator decision. Until one is made, the rows are kept.
- **Storage failure stops requests.** Because auditing fails closed (Q8), a database that cannot store them stops requests.

## Consequences

- **Tests that pin the behaviour.**
  - `RevocationBoundaryIT` shows a search admitting a row from the statement that ran before a revocation, and an answer keeping evidence
    revoked during generation. A change to either behaviour therefore has to change those tests deliberately.
  - `IndexProfileIT` shows the second configuration refused, two racing first writers with exactly one winner, and an emptied database
    keeping its profile.
- **Documents updated.** The threat model (A8), the overview, ADR-0006's consequence on citations, ADR-0007's deployment assumption and the
  known limitations state the above.
