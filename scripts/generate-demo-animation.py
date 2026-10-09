#!/usr/bin/env python3
"""Render the animated terminal at the top of the READMEs.

Every output line is copied from a file: the queries from a captured run of ./scripts/demo-queries, the table from a committed benchmark
report. Only the prompt lines and the `#` comments are written here, and the table cells are padded so that the columns line up.

    python3 scripts/generate-demo-animation.py assets/demo/demo-queries.txt benchmarks/reports/m3-retrieval/report.md assets/demo/terminal.svg
"""

import sys
from html import escape
from pathlib import Path

WIDTH = 900
LEFT = 22
TOP = 58
LINE = 18
LOOP = 30.0
FADE = 0.4
STEP = 0.22
COLORS = {"cmd": "#e6edf3", "head": "#d29922", "who": "#58a6ff", "hit": "#e6edf3", "text": "#8b949e", "row": "#c9d1d9", "note": "#3fb950"}


def kind(line: str) -> str:
    if line.startswith("$ "):
        return "cmd"
    if line.startswith("# "):
        return "note"
    if line.startswith("=="):
        return "head"
    if line.startswith("|") or line.startswith("##"):
        return "row"
    if line.startswith("     "):
        return "text"
    if line.startswith("  "):
        return "hit"
    return "who"


def section(lines: list[str], title: str) -> list[str]:
    start = lines.index(title)
    end = next((i for i in range(start + 1, len(lines)) if not lines[i]), len(lines))
    return lines[start:end]


def aligned(rows: list[str]) -> list[str]:
    cells = [[cell.strip() for cell in row.strip("|").split("|")] for row in rows]
    widths = [max(len(row[i]) for row in cells if set(row[i]) != {"-"}) for i in range(len(cells[0]))]
    widths[-1] = 0
    return ["| " + " | ".join(("-" * max(w, 3) if set(c) == {"-"} else c.ljust(w)) for c, w in zip(row, widths)).rstrip() + " |" for row in cells]


def scenes(demo: list[str], report: list[str]) -> list[tuple[float, list[str]]]:
    table = report.index("## Results (test split)")
    rows = [line for line in report[table + 2 :] if line.startswith("|")][:7]
    return [
        (9.0, ["$ ./scripts/demo-queries", *section(demo, "== Same question, two tenants =="), "# the outsider's rows never include a Northstar document"]),
        (9.0, [*section(demo, "== Same tenant, different clearance =="), "# same tenant, same question: only the token differs"]),
        (12.0, ["$ ./scripts/benchmark", "## Results (test split)", *aligned(rows), "# 0 unauthorized results in every row, so the security gate passes"]),
    ]


def render(demo: list[str], report: list[str]) -> str:
    texts: list[str] = []
    rules: list[str] = []
    still: list[str] = []
    tallest = 0
    begin = 0.0
    index = 0
    for number, (seconds, lines) in enumerate(scenes(demo, report)):
        end = begin + seconds
        tallest = max(tallest, len(lines))
        for row, line in enumerate(lines):
            shown = 100 * (begin + 0.3 + row * STEP) / LOOP
            hidden = 100 * (end - 0.5) / LOOP
            rules.append(
                f"@keyframes k{index}{{0%,{shown:.2f}%{{opacity:0}}{shown + FADE:.2f}%,{hidden:.2f}%{{opacity:1}}{hidden + FADE:.2f}%,100%{{opacity:0}}}}"
                f".l{index}{{animation:k{index} {LOOP:.0f}s linear infinite}}"
            )
            if number == 0:
                still.append(f".l{index}")
            texts.append(
                f'<text class="l l{index}" font-size="{11 if line.startswith("|") else 12}" x="{LEFT}" y="{TOP + row * LINE}" '
                f'fill="{COLORS[kind(line)]}" xml:space="preserve">{escape(line)}</text>'
            )
            index += 1
        begin = end
    height = TOP + tallest * LINE + 6
    style = (
        ".l{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,'DejaVu Sans Mono',monospace;opacity:0}"
        + "".join(rules)
        + "@media (prefers-reduced-motion:reduce){.l{animation:none!important}"
        + ",".join(still)
        + "{opacity:1}}"
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {WIDTH} {height}" width="{WIDTH}" height="{height}" role="img" '
        'aria-label="Terminal recording: the same question answered for different identities, then the benchmark with its security gate">'
        f"<style>{style}</style>"
        f'<rect width="{WIDTH}" height="{height}" rx="10" fill="#0d1117"/>'
        f'<rect width="{WIDTH}" height="34" rx="10" fill="#161b22"/><rect y="24" width="{WIDTH}" height="10" fill="#161b22"/>'
        '<circle cx="20" cy="17" r="6" fill="#ff5f56"/><circle cx="40" cy="17" r="6" fill="#ffbd2e"/><circle cx="60" cy="17" r="6" fill="#27c93f"/>'
        f'<text x="{WIDTH // 2}" y="21" text-anchor="middle" fill="#8b949e" font-family="ui-monospace,SFMono-Regular,Menlo,Consolas,monospace" '
        'font-size="12">grounded-access</text>' + "".join(texts) + "</svg>\n"
    )


def main() -> None:
    demo, report, out = (Path(p) for p in sys.argv[1:4])
    out.write_text(render(demo.read_text().splitlines(), report.read_text().splitlines()))


if __name__ == "__main__":
    main()
