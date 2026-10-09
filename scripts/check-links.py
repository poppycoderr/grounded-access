"""Checks every relative link and anchor in the tracked Markdown files: `python3 scripts/check-links.py`.

A link to a file must point at a tracked file or directory; a link with a fragment must match a heading of the target, using GitHub's
heading anchors. External links are not fetched here, so the check needs no network and cannot fail for reasons outside the repository.
"""

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r"\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)|(?:href|src)=\"([^\"]+)\"")
EXTERNAL = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def without_code(text: str) -> str:
    text = re.sub(r"(?ms)^```.*?^```", "", text)
    return re.sub(r"`[^`\n]*`", "", text)


def anchors(markdown: Path) -> set[str]:
    """GitHub's anchors: lowercase, punctuation removed, spaces to hyphens, repeated headings numbered."""
    seen: dict[str, int] = {}
    found: set[str] = set()
    for heading in re.findall(r"(?m)^#{1,6}\s+(.*?)\s*#*\s*$", re.sub(r"(?ms)^```.*?^```", "", markdown.read_text())):
        text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)
        slug = re.sub(r"[^\w\- ]", "", re.sub(r"[`*_]", "", text).lower()).replace(" ", "-")
        count = seen.get(slug, 0)
        seen[slug] = count + 1
        found.add(slug if count == 0 else f"{slug}-{count}")
    return found


def main() -> int:
    tracked = [ROOT / name for name in subprocess.run(["git", "ls-files", "*.md"], cwd=ROOT, check=True, capture_output=True, text=True).stdout.split()]
    problems: list[str] = []
    checked = 0
    for markdown in tracked:
        for match in LINK.finditer(without_code(markdown.read_text())):
            target = match.group(1) or match.group(2)
            if EXTERNAL.match(target):
                continue
            checked += 1
            path, _, fragment = unquote(target).partition("#")
            resolved = (markdown.parent / path).resolve() if path else markdown
            where = f"{markdown.relative_to(ROOT)}: {target}"
            if not resolved.exists():
                problems.append(f"{where} — no such file")
            elif ROOT not in resolved.parents and resolved != ROOT:
                problems.append(f"{where} — points outside the repository")
            elif fragment and resolved.suffix == ".md" and fragment.lower() not in anchors(resolved):
                problems.append(f"{where} — no heading with that anchor")
    for problem in problems:
        print(problem, file=sys.stderr)
    print(f"{checked} relative links in {len(tracked)} files, {len(problems)} broken")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
