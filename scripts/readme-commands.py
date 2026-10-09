"""Prints the shell commands of a README's quick start, so CI can run exactly what a reader is told to run.

    python3 scripts/readme-commands.py README.md | bash -euo pipefail
    python3 scripts/readme-commands.py README.md --same-as README.zh-CN.md

The quick start is the second-level section that starts the stack. Trailing comments are dropped. Cloning the repository and entering it are
skipped, because CI already runs inside a checkout.
"""

import re
import sys
from pathlib import Path

START = "docker compose up -d --build --wait"
SKIPPED = ("git clone ", "cd grounded-access")


def commands(readme: Path) -> list[str]:
    sections = re.split(r"(?m)^## ", readme.read_text())
    matching = [section for section in sections if START in section]
    if len(matching) != 1:
        raise SystemExit(f"{readme}: expected one section containing `{START}`, found {len(matching)}")
    lines: list[str] = []
    for block in re.findall(r"(?ms)^```bash\n(.*?)^```", matching[0]):
        for line in block.splitlines():
            line = re.sub(r"\s{2,}#.*$", "", line).rstrip()
            if line and not line.startswith(SKIPPED):
                lines.append(line)
    return lines


def main() -> None:
    found = commands(Path(sys.argv[1]))
    if len(sys.argv) == 4 and sys.argv[2] == "--same-as":
        other = commands(Path(sys.argv[3]))
        if found != other:
            differing = [f"  {a!r}\n  {b!r}" for a, b in zip(found, other, strict=False) if a != b]
            raise SystemExit(f"{sys.argv[1]} and {sys.argv[3]} tell readers to run different commands:\n" + "\n".join(differing or ["  (different number of commands)"]))
        return
    print("\n".join(found))


if __name__ == "__main__":
    main()
