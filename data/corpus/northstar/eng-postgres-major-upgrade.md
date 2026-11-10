# Runbook: PostgreSQL Major Version Upgrade

This fictional runbook describes how Northstar Cloud engineering upgrades a PostgreSQL cluster to a new major version. It is written for the database team and for service owners whose service has its own cluster. It does not cover the billing database, whose upgrades are planned separately.

## When to upgrade

A cluster is upgraded to a new major version no later than nine months after that version is released, and never before its second minor release. The database team keeps the upgrade calendar and opens a ticket for every cluster three months before its deadline.

## Preparation

Preparation starts two weeks before the upgrade. The service owner reads the release notes of every major version between the current one and the target, and lists each change that could affect the service: removed functions, changed defaults, changes in how the planner treats the queries the service runs most, and extensions that need a new version of their own. The database team restores the most recent snapshot of the cluster into a test environment, upgrades that copy with the same procedure that will be used in production, and runs the service's integration tests against it. The test copy also runs a replay of one hour of production queries captured from the slow query log, and the team compares the 95th percentile duration of each query shape before and after the upgrade. A query shape that becomes more than 50 percent slower blocks the upgrade until the service owner has rewritten the query or the team has found a setting that restores the old plan. Each blocked shape gets its own ticket so that the reason for any delay is visible. The rehearsal is repeated until it passes without a blocked shape, and the duration of the last rehearsal becomes the estimate for the maintenance window, with a margin of half that duration added on top.

## Checks before the window

Run these checks on the primary no more than one hour before the window starts:

```sql
-- every extension must have a version available on the target
SELECT name, installed_version, default_version
FROM pg_available_extensions
WHERE installed_version IS NOT NULL;

-- no prepared transaction may be left open
SELECT gid, prepared FROM pg_prepared_xacts;

-- replication slots that are not active block the upgrade
SELECT slot_name, active FROM pg_replication_slots WHERE NOT active;
```

If the second query returns any row, stop and ask the owner of that transaction to commit or roll it back. If the third query returns any row, drop the inactive slot only after its owner has confirmed that it is no longer used.

## The upgrade

1. Set the cluster to read-only in the service registry, so that clients receive a clear error instead of timeouts.
2. Stop the service's background workers.
3. Take a final snapshot and record its identifier in the upgrade ticket.
4. Run the in-place upgrade with hard links, using the command recorded in the rehearsal.
5. Start the cluster on the new version and run `ANALYZE` on every database before any client is allowed back.
6. Set the cluster back to read-write and restart the background workers.

## Rolling back

Once the cluster has accepted writes on the new version, there is no rollback to the old version. Before that point, the team restores the snapshot taken in step 3 and starts the old version on it. The decision to roll back is made by the database on-call engineer and must be taken within 30 minutes of the start of step 4; after that the team fixes forward.

## After the upgrade

For one week after the upgrade the database team compares the query durations of the service with those of the week before, and the service owner watches the error rate. Replicas are rebuilt from the new primary within two days.
