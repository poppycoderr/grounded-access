# ADR-0013: How audit rows are kept, exported and removed

- Status: Accepted
- Date: 2026-11-05

## Context

Every search writes one `query_execution` row and one `audit_event` row. An answer adds one more event, and administrative actions and
document reads add theirs. Both tables only grow: nothing deletes from them (ADR-0011, "Audit rows are kept"). Auditing is synchronous and
fails closed (Q8), so a database that cannot store a row refuses the request. Each audit statement also has its own
timeout (`GA_AUDIT_SQL_TIMEOUT`), so a slow write refuses its request instead of holding it.

Review 2026-10-21 (D5) asked for retention, export and cleanup. It warned against two shortcuts:

- giving audit rows the short retention that traces have;
- choosing a deletion period on the operator's behalf.

No measurement shows table growth hurting the demo yet. At about two rows per search the tables stay small at demo scale, but they grow
without bound in a deployment that keeps running.

The two tables are not the same kind of record:

| | `audit_event` | `query_execution` |
|---|---|---|
| Purpose | Accountability: who did what to which resource, allowed or denied, under which policy version | Diagnosis and reproduction: plan, profile, models, degraded reasons, timings |
| Read by | Operators and reviewers, at the database | The principal that made the request (`GET /api/v1/query-executions/{id}`), and evaluation |
| Stands alone | Yes. A search event repeats strategy, `k`, plan hash, result count and document keys | No. It is meant to be read next to its trace and its event |

## Decision

### Keeping rows is the default

- **No setting, no deletion.** Retention is off unless an operator configures it, and nothing is ever deleted by default. Upgrading never
  deletes a row.
- **One setting per table.** Retention is configured apart for each table, because the two serve different purposes: `GA_AUDIT_RETENTION`
  and `GA_EXECUTION_RETENTION`, as ISO-8601 durations.
- **A floor against typos.** The control plane refuses to start with a retention under one day. A shorter period is not an audit retention.
- **No order between the tables.** An execution record may be removed before its event, because the event stands alone. An event whose
  execution record is gone still names it, and `GET /api/v1/query-executions/{id}` then answers 404 for it, as it does for any id that is not
  the caller's.

### Rows are exported before they are removed

- **What an export is.** It writes every row of both tables created before a cut-off `through` to newline-delimited JSON, one file per
  table, and a manifest with each file's row count and SHA-256. The rows are read in one `REPEATABLE READ` transaction, so the files and the
  manifest describe one snapshot.
- **Who runs it.** Exporting is an operator task at the database, not an API endpoint. The audit covers every tenant, and an endpoint would
  need a cross-tenant administrator that the identity model does not have (Q7).
- **What it records.** In the same transaction, the export inserts a row into a new table `audit_export`: `through`, the row counts, the
  manifest's SHA-256 and when it ran. The export is the evidence that rows were taken off the database before anything deletes them.
- **Where the files go.** The project writes the files. Where they are kept, and for how long, is the operator's decision.
- **How old the cut-off must be.** At least one hour, by the database clock. A transaction that started before the cut-off and commits
  after the export's snapshot carries a timestamp before the cut-off but is not in the snapshot; a cleanup relying on the export would
  remove it unexported. One hour is longer than any transaction that writes audit rows. Added while implementing item 1.
- **Each export is complete.** It covers every row before its cut-off, not only rows since the previous export, so any single export is
  enough to restore what it covers. Its files are never overwritten, and an export that fails records nothing and leaves no files.

### Cleanup removes only what is old and exported

- **What may be deleted.** A row is deleted only when it is older than its table's retention **and** older than the latest recorded
  export's `through`. Without an export nothing is deleted, whatever the retention says. `GA_AUDIT_CLEANUP_REQUIRE_EXPORT=false` drops the
  second condition, and the control plane logs a warning at startup when it is set.
- **How it runs.** A scheduled job outside the request path, like corpus cleanup, deletes in batches of a bounded size. Each batch is its
  own short transaction with the audit statement timeout. It never waits for a request, and a request never waits for it.
- **What it leaves behind.** Each batch writes an `audit.purge` event with the table, the cut-off and the number of rows removed, in the
  transaction that removed them, so a deletion is never unaudited. The event is a system action: its tenant is empty, which no token can
  carry, and its principal is `system:audit-cleanup`. It is newer than any cut-off, so the run does not remove it.
- **It never waits.** A batch skips rows that another transaction holds (`FOR UPDATE SKIP LOCKED`); a later run removes them.
- **No partitioning yet.** Batched deletes are enough until a load evaluation shows that they are not. Partitioning by month would make
  removal cheap, but it changes the primary keys and the migrations, and nothing measured asks for it.

### Storage is watched, not guessed

- **Metrics.** Two gauges, `ga.audit.rows` (an estimate from the table statistics, per table) and `ga.audit.oldest_age`, and panels on
  the Grafana dashboard. They are refreshed on a schedule, so exporting metrics runs no SQL. Finding the oldest row, like cleanup and
  export, reads the tables by time across tenants, so each table has an index on its timestamp (migration V16).
- **Alerting is the operator's.** The failure stays as it is: auditing fails closed, so a full disk refuses requests.

### What this does not change

- **Tamper evidence.** Rows stay ordinary rows that a database administrator can alter (threat model, R6). An export file is a copy, not a
  signature; its SHA-256 shows only that the file matches what was recorded at export time.
- **Contents.** Exported rows hold what the tables hold: identifiers, counts and versions, never query text, chunk text, titles or
  embeddings (rule 5).

## Delivery

This ADR is the contract. The implementation is split into three v0.2 items, in this order:

1. `audit_export` (migration V15), the export command (`scripts/export-audit`, the control plane under the `audit-export` profile),
   and tests that the files, the manifest and the recorded row agree. **Delivered.**
2. The retention settings with their floor, the cleanup job, `audit.purge`, and tests (**delivered**):
   - nothing is deleted without retention;
   - nothing is deleted without an export;
   - only rows past both conditions are deleted;
   - a batch is bounded and does not block a search.
3. The two gauges and the dashboard panels, with the time indexes they and cleanup need (**delivered**).

All three items are delivered. Without a configured retention nothing deletes rows.

## Consequences

- A deployment can bound the audit tables without losing the only copy of a row.
- An operator who never configures retention keeps today's behaviour.
- Removing execution records makes old execution ids answer 404. Evaluation reports hold their own copy of what they measured (ADR-0008),
  so they do not depend on execution rows.
- New settings: `GA_AUDIT_RETENTION`, `GA_EXECUTION_RETENTION`, `GA_AUDIT_CLEANUP_REQUIRE_EXPORT`. New table: `audit_export`. New audit
  action: `audit.purge`.
