# Security Policy

Grounded Access is a reference system under active development. It has not been hardened for production use, and the demo identity setup (locally signed JWTs with a published demo key) is insecure by design.

## Reporting a vulnerability

Please report vulnerabilities privately through [GitHub security advisories](https://github.com/poppycoderr/grounded-access/security/advisories/new) rather than in public issues.

Reports that are especially valuable:

- any way to retrieve, cite or infer the existence of content that the authorization model says should be hidden;
- cross-tenant leakage through any endpoint, log, trace or metric;
- sensitive text (queries, document content, prompts) appearing in telemetry.

Expect an acknowledgement within 7 days. Fixes land on `main`; there are no maintained release branches before v1.0.
