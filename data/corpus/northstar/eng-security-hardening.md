# Production Hardening Standard

This fictional standard is for the Northstar Cloud engineering department.

## SSH access

Engineers reach production hosts through the bastion only, and sessions are recorded. Direct SSH from a laptop to a production host is blocked at the network level.

## Secrets

Secrets are stored in the vault and rotated every 90 days. A secret that appears in a log or a chat message is rotated within one hour.

## Patching

Critical security patches are applied to production within 72 hours of release. Other patches follow the monthly maintenance window.
