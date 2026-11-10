# Project Atlas: Rollback Criteria

This fictional document is for members of Project Atlas only. It defines when the team returns billing traffic to the old database after the switch to the new cluster.

## Criteria

The team rolls back if the error rate of the invoice API stays above one percent for ten minutes, if any validation run reports a mismatch in the payment table, or if the replication from the new cluster back to the old one falls more than five minutes behind.

## Who decides

The Atlas technical lead decides on a rollback. If the lead cannot be reached within ten minutes, the database on-call engineer decides.

## How

A rollback points the billing service back at the old cluster through the service registry. Writes made to the new cluster since the switch are replayed on the old cluster from the reverse replication stream before the switch back.
