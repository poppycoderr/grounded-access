# Project Borealis: Launch Runbook

This fictional runbook is for members of project Borealis, the usage-based pricing engine that has not been announced yet. It is confidential.

## Launch gates

Borealis launches only after the metering pipeline has processed 30 days of shadow traffic with fewer than 5 mismatched invoices per million.

## Kill switch

The feature flag `borealis.metering.enabled` turns the engine off within one minute. Only the Borealis on-call engineer may flip it during the first two weeks after launch.

## Failover

If the metering database fails, usage events are buffered in the queue for up to 6 hours. The engineer does not fail over the billing database for a Borealis outage.
