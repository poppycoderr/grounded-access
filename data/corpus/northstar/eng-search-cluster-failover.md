# Runbook: Search Cluster Failover

Use this runbook when the primary search cluster serving customer invoices search is red for more than ten minutes.

## Before failing over

1. Check whether the cluster is red because of a single unassigned shard; if so, reroute the shard instead of failing over.
2. Announce the failover in the #inc-search channel.

## Failing over

Switch the search alias to the standby cluster in the search console. The standby cluster is refreshed from snapshots every hour, so results can be up to one hour stale.

## After failing over

Rebuild the failed cluster from the latest snapshot and re-point the alias only after a full reindex has finished.
