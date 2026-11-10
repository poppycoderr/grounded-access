# Service Ownership

This fictional register lists which Northstar Cloud team owns each production service and where its runbooks live. The owning team is paged for the service and approves changes to its pipeline.

## Owners

| Service | Owning team | Runbook folder | Escalation after 30 minutes |
|---|---|---|---|
| invoice-worker | team-billing | runbooks/billing/invoice-worker | head of billing engineering |
| invoice-api | team-billing | runbooks/billing/invoice-api | head of billing engineering |
| payment-gateway | team-payments | runbooks/payments/gateway | head of payments |
| search-api | team-search | runbooks/search/api | head of platform |
| search-indexer | team-search | runbooks/search/indexer | head of platform |
| identity | team-identity | runbooks/identity/core | chief information security officer |
| metering | team-usage | runbooks/usage/metering | head of platform |
| edge-gateway | team-platform | runbooks/platform/edge | head of platform |

## Changing an owner

A service changes owner only when both teams' leads have signed the handover in the register. The old owner stays on the escalation path for 30 days after the handover, so that knowledge that was never written down can still be reached.

## Services without an owner

A production service without an owning team is not allowed. If a team is dissolved, its services move to team-platform until a new owner is found, and no new features are deployed to them in the meantime.
