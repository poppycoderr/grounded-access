# Log Retention Standard

This fictional standard says how long Northstar Cloud keeps each kind of log, where the logs are stored and who may read them. It applies to logs written by services, not to the data those services store for customers.

## Retention per log class

| Log class | Example index | Retention | Readable by |
|---|---|---|---|
| application | `logs-app-invoice-worker-2026.03.12` | 30 days | the owning team |
| access | `logs-access-edge-gateway-2026.03.12` | 90 days | security and the owning team |
| audit | `logs-audit-identity-2026.03.12` | 400 days | security only |
| debug | `logs-debug-search-indexer-2026.03.12` | 7 days | the owning team |

Retention is counted from the day a line is written. An index older than its retention is deleted by the nightly cleanup job, which records every deleted index name in its own audit log.

## Naming

An index name is built from the log class, the service name and the date, joined without spaces, for example `logs-access-edge-gateway-2026.03.12`. A service must not write to an index of another class, and it must not create indexes outside this naming scheme; the cleanup job ignores such indexes, so they would never be deleted.

## Legal holds

The security team may place a legal hold on an index. A held index is skipped by the cleanup job until the hold is lifted, and the hold is reviewed every 90 days. Only the head of security can lift a hold.

## What must never be logged

Passwords, session tokens, full card numbers and the body of customer documents must never be written to any log class. A line that contains one of them is a security incident and is reported to the security team on the day it is found.
