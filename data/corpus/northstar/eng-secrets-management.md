# Secrets Management

This fictional standard is for engineering only. It describes how Northstar Cloud services store and use passwords, credentials and other secrets.

## Where secrets live

Every secret is stored in the central secret store. Secrets are never written into a repository, a container image, a ticket or a chat message. A service reads its secrets at startup through its workload identity; no person needs to copy a secret by hand for a deployment.

## Rotation

Database passwords are rotated every 90 days and credentials for external providers every 180 days. The secret store rotates database passwords on its own; provider credentials are rotated by the owning team, which receives a reminder 14 days before the deadline.

## A secret in the wrong place

When a secret is found in a repository or a log, the owning team rotates it within four hours, whether or not anyone could have read it. Deleting the commit or the log line is not enough, because copies may exist elsewhere. The finding is also reported to the security team.

## Access to the secret store

Engineers can read the names of secrets for their own services but not their values. Reading a value requires a break-glass request, which is approved by a second engineer and expires after one hour.
