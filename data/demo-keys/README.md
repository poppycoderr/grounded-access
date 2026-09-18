# Demo signing keys

**These keys are public and insecure by design.** They exist so that anyone can mint demo tokens for the fictional Northstar principals without running an identity provider.

- `jwt-private.pem` signs tokens (`scripts/mint-token.py`).
- `jwt-public.pem` verifies them; the control plane ships a copy in `apps/control-plane/src/main/resources/demo-keys/` and uses it unless `GA_JWT_PUBLIC_KEY` points elsewhere.

Never reuse these keys outside local demos. Real deployments configure their own verification key or an OIDC provider.
