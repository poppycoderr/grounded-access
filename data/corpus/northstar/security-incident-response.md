# Security Incident Response Plan

This fictional plan describes how Northstar Cloud responds to a security incident: an event that threatens the confidentiality, integrity or availability of customer data or of the systems that hold it. Operational outages without a security cause follow the on-call handbook instead.

## Security severities

| Severity | Definition | Response team assembled within |
|---|---|---|
| S1 | Customer data has left the company, or an attacker controls a production system | 1 hour |
| S2 | An attacker has reached an internal system but no customer data is known to be affected | 4 hours |
| S3 | A vulnerability is being exploited somewhere, but not against Northstar Cloud | 1 business day |
| S4 | A suspicious event that has not been confirmed | 2 business days |

Security severities are separate from the operational severity levels used by on-call engineers. One event can carry both.

## Reporting

Anyone who suspects a security incident reports it in the #security-report channel or by email to the security team, at any time of day. Nobody is blamed for a report that turns out to be harmless. The security engineer on duty acknowledges the report within 30 minutes and assigns the first severity.

## Preserving evidence

Evidence decides what can later be proven about an incident, so it is collected before anything is cleaned up, even when cleaning up would end the incident sooner. The first responder does not shut down, restart or reinstall an affected machine. A shutdown erases the memory of the machine, and with it the running processes, open network connections and keys that an attacker may have loaded; a reinstall erases the disk as well. Instead, the responder isolates the machine from the network by moving it into the quarantine security group, which blocks every connection except the one used by the forensics tools. The responder then takes a memory image and a disk snapshot, in that order, because the memory changes faster than the disk. Both are written to the evidence bucket, which only the security team can read and from which nothing can be deleted for one year. The responder records the identifier of each image in the incident ticket together with the time it was taken and a hash of its contents, so that anyone can later check that the image has not been changed. Logs that relate to the incident are copied into the evidence bucket as well, since their normal retention may be shorter than the investigation. When a cloud account rather than a machine is affected, the responder disables the compromised credentials but does not delete them, and exports the account's activity history for the previous 90 days into the evidence bucket before changing anything else in the account. Screenshots of consoles are accepted as supporting evidence but never replace an export. Every person who handles evidence writes their name, the time and what they did into the chain-of-custody section of the incident ticket, and nobody copies evidence to a laptop or to any storage outside the evidence bucket.

## Containment and recovery

After the evidence is preserved the response team contains the incident: it rotates every credential that the attacker could have read, blocks the addresses and accounts that were used, and removes the attacker's access. Affected machines are rebuilt from clean images rather than repaired. A machine returns to service only after the security lead has signed off in the incident ticket.

## Notification

If customer personal data is affected, the legal team notifies the supervisory authority within 72 hours of the moment the incident was confirmed as S1. Affected customers are told without undue delay, and in any case within five business days, unless the authority asks the company to wait.

## After the incident

Every S1 and S2 incident gets a written review within ten business days. The review lists what happened, what was missing in detection or response, and an owner and a date for every follow-up action.
