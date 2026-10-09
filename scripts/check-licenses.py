"""Checks the licenses of every Maven and PyPI component in CycloneDX SBOM files against an allow-list.

    python3 scripts/check-licenses.py ops/supply-chain/licenses.json sbom/*.cdx.json

A component passes if its license is on the list. Several licenses on one component are a choice, as is an expression with OR; an
expression with AND needs every part. A component without a recognizable license fails until `overrides` names its license and says how
that was established. Components of other ecosystems (for example the crates inside a bundled tool) are counted but not judged.
"""

import json
import re
import sys
from pathlib import Path

JUDGED = ("pkg:maven/", "pkg:pypi/")


def declared(component: dict) -> list[str]:
    found = []
    for entry in component.get("licenses", []):
        if "expression" in entry:
            found.append(entry["expression"])
        elif "license" in entry:
            found.append(entry["license"].get("id") or entry["license"].get("name") or "")
    return [license for license in found if license]


def permitted(expression: str, allowed: set[str]) -> bool:
    parts = [part for part in re.split(r"\s+(?:AND|OR|WITH)\s+|[()]", expression) if part.strip()]
    if not parts:
        return False
    verdicts = [part.strip() in allowed for part in parts]
    return any(verdicts) if re.search(r"\sOR\s", expression) else all(verdicts)


def main() -> int:
    policy = json.loads(Path(sys.argv[1]).read_text())
    allowed = set(policy["allowed"])
    problems: list[str] = []
    judged = skipped = 0
    for path in sys.argv[2:]:
        for component in json.loads(Path(path).read_text()).get("components", []):
            purl = component.get("purl", "")
            if not purl.startswith(JUDGED):
                skipped += 1
                continue
            judged += 1
            name = purl.split("@")[0].split("?")[0]
            override = policy["overrides"].get(name)
            licenses = [override["license"]] if override else declared(component)
            if not any(permitted(license, allowed) for license in licenses):
                problems.append(f"{path}: {purl} — {', '.join(licenses) or 'no license declared'}")
    for problem in sorted(set(problems)):
        print(problem, file=sys.stderr)
    print(f"{judged} Maven and PyPI components checked, {len(set(problems))} outside the allow-list; {skipped} components of other ecosystems not judged")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
