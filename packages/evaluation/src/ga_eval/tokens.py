"""Demo tokens for evaluation principals, signed with the public demo key (same claims as scripts/mint-token.py)."""

import time
from pathlib import Path

import jwt

ISSUER = "grounded-access-demo"
AUDIENCE = "grounded-access"


def mint(root: Path, principal: str, attributes: dict, scope: str | None = None, ttl_seconds: int = 3600) -> str:
    now = int(time.time())
    claims = {"iss": ISSUER, "aud": AUDIENCE, "sub": principal, "iat": now, "exp": now + ttl_seconds, **attributes}
    if scope is not None:
        claims["scope"] = scope
    key = (root / "demo-keys" / "jwt-private.pem").read_text()
    return jwt.encode(claims, key, algorithm="RS256", headers={"typ": "JWT"})
