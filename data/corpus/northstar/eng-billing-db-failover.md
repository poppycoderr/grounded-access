# Runbook: Billing Database Failover

Use this runbook when the primary billing database is unavailable for more than five minutes and the automated failover has not started.

## Before failing over

1. Freeze all deploys by setting the deploy lock in the release dashboard.
2. Announce the failover in the #inc-billing channel and page the database on-call engineer.
3. Confirm that the replica lag is below 30 seconds; if it is higher, escalate to the incident commander before continuing.

## Failing over

Promote the standby replica with the promote command in the database console and point the billing service at the new primary through the service registry.

## After failing over

1. Verify that invoices are being written by checking the billing write-rate dashboard.
2. Rebuild a new standby from the latest snapshot within four hours.
3. Update the status page and file a postmortem ticket within one business day.
