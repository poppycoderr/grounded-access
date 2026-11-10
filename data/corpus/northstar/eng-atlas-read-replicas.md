# Project Atlas: Read Replicas

This fictional document is for members of Project Atlas only.

## Replicas

The new billing cluster has two read replicas, one in each availability zone of the primary region.

## Reporting queries

Reporting queries are moved to the replicas before the switch, so that the primary only carries the billing workload.

## Lag

Reports read from a replica show the time of the last replicated change at the top of the report.
