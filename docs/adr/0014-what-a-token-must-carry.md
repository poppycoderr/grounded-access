# ADR-0014: What a token must carry

- Status: Accepted
- Date: 2026-11-12

## Context

Rule 2 bases every authorization decision on the verified token: its attributes become the bound parameters of the authorization predicate. Until now, verification checked the signature, the issuer, the audience, a subject and a tenant. Everything else was the framework's default behaviour (review 2026-11-10, P1-6):

- **No expiry required.** The default time check runs only when the token has an `exp` claim. A token without one verified and stayed valid forever. Tokens cannot be revoked (threat model, R0), so an expiry is the only thing that ends a token.
- **Claims read as strings.** The control plane read each attribute with `getClaimAsString`, which turns any JSON value into a string. A `tenant_id` sent as an array became the text `["northstar"]` and was written to the audit table as a tenant. A `clearance` or `department` sent as an array was read as an unknown value. Each case narrowed access, but the reading was an accident of the conversion, not a decision.

With the demo identity anyone can sign any token, so this added no attack. It would carry over to an OIDC integration unchanged.

## Decision

A token is verified, or rejected with `401 invalid_token`, by these rules on top of the signature, issuer and audience checks:

| Claim | Required | Type |
|---|---|---|
| `exp` | yes | a time; the token is rejected after it, with the framework's default clock skew of 60 s |
| `sub` | yes | a non-blank string |
| `tenant_id` | yes | a non-blank string |
| `department` | no | a string |
| `clearance` | no | a string |
| `region` | no | a string |
| `projects` | no | an array of strings |
| `scope` | no | as before: the framework reads it into authorities; a token without the scope an endpoint needs gets `403` |

- **Wrong types are rejected, not converted.** A claim of another JSON type (array, number, boolean, object, or an array with a non-string element) rejects the whole token. No attribute is ever derived from a claim by turning it into a string.
- **Values are not checked here.** An unknown `clearance` value still grants the lowest level, and an unknown department or project matches no document (`Classification.clearanceOf`, `docs/architecture/authorization.md`). Those rules fail closed already; rejecting unknown values would tie token verification to the label vocabulary of the corpus.
- **One place.** `TokenClaims` holds these rules and runs inside the JWT decoder, so no request reaches a controller with a token that breaks them. `Principal.from` reads only claims it has checked.

## Consequences

- Tokens without `exp` stop working. Both token tools (`scripts/mint-token`, `ga-eval`) have always set it.
- A malformed token is now refused instead of answered with fewer results. The audit table never receives a tenant that is not a plain string.
- Not decided here: a maximum token lifetime, `nbf` and `iat` requirements, and how an OIDC provider's claim names map to these. They belong to the OIDC integration (Q7).
- Tests: unit tests for each rule; a property test that a token is accepted exactly when each attribute has its type, and that the principal then holds the claims unchanged; integration tests that the search endpoint refuses each kind of malformed token and still answers a well-formed one.
