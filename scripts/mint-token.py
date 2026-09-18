# /// script
# requires-python = ">=3.12"
# dependencies = ["pyjwt[crypto]>=2.10", "pyyaml>=6"]
# ///
"""Mint a demo JWT for a principal in data/principals.yaml, signed with the public demo key.

    uv run scripts/mint-token.py alice-engineer
    uv run scripts/mint-token.py alice-engineer --scope "query debug"
"""

import argparse
import sys
import time
from pathlib import Path

import jwt
import yaml

ROOT = Path(__file__).resolve().parent.parent
ISSUER = "grounded-access-demo"
AUDIENCE = "grounded-access"


def mint(principal: str, scope: str | None = None, ttl_seconds: int = 3600) -> str:
    principals = yaml.safe_load((ROOT / "data" / "principals.yaml").read_text())["principals"]
    if principal not in principals:
        raise SystemExit(f"unknown principal {principal!r}; known: {', '.join(sorted(principals))}")
    attributes = dict(principals[principal])
    now = int(time.time())
    claims = {"iss": ISSUER, "aud": AUDIENCE, "sub": principal, "iat": now, "exp": now + ttl_seconds, **attributes}
    if scope is not None:
        claims["scope"] = scope
    key = (ROOT / "data" / "demo-keys" / "jwt-private.pem").read_text()
    return jwt.encode(claims, key, algorithm="RS256", headers={"typ": "JWT"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("principal")
    parser.add_argument("--scope", help="override the principal's scope, e.g. 'query debug'")
    parser.add_argument("--ttl", type=int, default=3600, help="lifetime in seconds")
    args = parser.parse_args()
    sys.stdout.write(mint(args.principal, args.scope, args.ttl) + "\n")
