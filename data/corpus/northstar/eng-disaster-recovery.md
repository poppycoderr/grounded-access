# Disaster Recovery Plan

This fictional plan describes how Northstar Cloud restores its services after the loss of a whole region.
From this version on the plan is for engineering only, because it lists the recovery order and the standby locations of every service.

## Recovery tiers

Services are grouped into three tiers. Tier 1 services are restored first, within 30 minutes. Tier 2 services are restored within four hours, and tier 3 services within two days.

## Recovery targets per service

The recovery time objective (RTO) is the longest time a service may be unavailable, and the recovery point objective (RPO) is the most data, measured in minutes of writes, that it may lose. The targets are kept in one file that the recovery tooling reads:

```yaml
recovery:
  - service: billing-db
    tier: 1
    rto_minutes: 15
    rpo_minutes: 5
    standby_region: eu-west
    restore_from: replica
  - service: invoice-api
    tier: 1
    rto_minutes: 30
    rpo_minutes: 5
    standby_region: eu-west
    restore_from: replica
  - service: payment-gateway
    tier: 1
    rto_minutes: 15
    rpo_minutes: 1
    standby_region: eu-west
    restore_from: replica
  - service: identity
    tier: 1
    rto_minutes: 15
    rpo_minutes: 1
    standby_region: eu-west
    restore_from: replica
  - service: search-api
    tier: 2
    rto_minutes: 120
    rpo_minutes: 60
    standby_region: eu-west
    restore_from: snapshot
  - service: search-indexer
    tier: 2
    rto_minutes: 240
    rpo_minutes: 60
    standby_region: eu-west
    restore_from: snapshot
  - service: notification
    tier: 2
    rto_minutes: 120
    rpo_minutes: 15
    standby_region: eu-west
    restore_from: snapshot
  - service: metering
    tier: 2
    rto_minutes: 60
    rpo_minutes: 15
    standby_region: eu-west
    restore_from: snapshot
  - service: pricing-engine
    tier: 2
    rto_minutes: 60
    rpo_minutes: 15
    standby_region: eu-west
    restore_from: snapshot
  - service: edge-gateway
    tier: 1
    rto_minutes: 15
    rpo_minutes: 1
    standby_region: eu-west
    restore_from: replica
  - service: admin-console
    tier: 3
    rto_minutes: 1440
    rpo_minutes: 240
    standby_region: none
    restore_from: snapshot
  - service: reporting
    tier: 3
    rto_minutes: 1440
    rpo_minutes: 1440
    standby_region: none
    restore_from: snapshot
  - service: wiki
    tier: 3
    rto_minutes: 2880
    rpo_minutes: 1440
    standby_region: none
    restore_from: snapshot
  - service: build-runners
    tier: 3
    rto_minutes: 720
    rpo_minutes: 240
    standby_region: none
    restore_from: snapshot
  - service: artifact-store
    tier: 2
    rto_minutes: 240
    rpo_minutes: 60
    standby_region: eu-west
    restore_from: snapshot
  - service: log-pipeline
    tier: 3
    rto_minutes: 720
    rpo_minutes: 60
    standby_region: none
    restore_from: snapshot
```

A service that is not in this file has no recovery target and is restored last.

## Declaring a disaster

The incident commander declares a disaster when a region has been unavailable for 30 minutes and the cloud provider gives no recovery estimate.

## Exercises

The plan is exercised twice a year. One exercise restores every tier 1 service in the standby region; the other restores one tier 2 service chosen at random, without warning the owning team in advance.
