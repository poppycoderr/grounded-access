"""Generated distractor documents for scale and load runs (v0.2 plan, step 1a).

They add volume, not answers: no case cites them, and they are never part of the labelled dataset. They carry access labels like real
documents, so the authorization predicate does real work at scale, and their visibility per principal is written out by `visible`, a direct
reading of the decision table in docs/architecture/authorization.md that does not use the policy compiler.

Everything is drawn from `random.Random(seed)` over fixed fictional vocabularies, so a seed always writes the same bytes. Nothing generated
is committed; a run names the seed and the document count instead.
"""

import random
from dataclasses import dataclass
from pathlib import Path

import yaml

PREFIX = "distractor-"

CLEARANCE = {"public": 0, "internal": 1, "confidential": 2, "restricted": 3}

# Labels and their weights. Restricted documents are readable by no demo principal; about 2% of documents need project atlas, the selective
# filter that ADR-0002 asks to measure.
CLASSIFICATIONS = (("public", 15), ("internal", 60), ("confidential", 15), ("restricted", 10))
DEPARTMENTS = ("engineering", "support", "sales")
DEPARTMENT_SHARE = 0.15
PROJECT_SHARES = (("atlas", 0.02), ("borealis", 0.02))
EXTERNAL_SHARE = 0.10

SYSTEMS = (
    "Kestrel queue",
    "Larkspur ledger",
    "Tamarack scheduler",
    "Quillon gateway",
    "Marrow cache",
    "Ostrich importer",
    "Pellucid index",
    "Fennel console",
    "Wicket relay",
    "Saffron archive",
    "Brindle mailer",
    "Juniper vault",
    "Cobalt planner",
    "Thistle exporter",
)
DOCUMENT_TYPES = ("Operating Notes", "Design Review", "Field Guide", "Change Log", "Working Agreement", "Capacity Notes", "Retrospective")
TEAMS = ("the platform group", "the billing pod", "the field team", "the data guild", "the release crew", "the night shift", "the help desk")
ACTIONS = (
    "rotates its staging credentials",
    "drains the overflow partition",
    "replays the nightly batch",
    "pauses the regional sync",
    "re-indexes the archive tier",
    "rebalances the worker pool",
    "trims the audit spool",
    "rebuilds the warm standby",
)
CONDITIONS = (
    "after a planned maintenance window",
    "when the backlog passes {n} items",
    "every {n} days",
    "before the quarterly review",
    "if the error rate stays above {n} per minute",
    "once {n} tickets are open",
    "during the {n}-hour freeze",
)
OUTCOMES = (
    "keeps the dashboards green",
    "avoids a second pager rotation",
    "shortens the handover by {n} minutes",
    "frees about {n} gigabytes",
    "removes a manual step from the checklist",
    "lets the next shift start from a clean state",
)
HEADINGS = ("Purpose", "Background", "Procedure", "Exceptions", "Ownership", "History", "Open questions", "Checks", "Contacts", "Glossary")


@dataclass(frozen=True)
class Document:
    key: str
    tenant: str
    title: str
    content: str
    classification: str
    allowed_departments: list[str]
    required_projects: list[str]


@dataclass(frozen=True)
class Generated:
    seed: int
    documents: list[Document]
    principals: dict[str, dict]


def generate(seed: int, count: int, principals: dict[str, dict]) -> Generated:
    rng = random.Random(seed)
    documents = [_document(rng, number) for number in range(count)]
    return Generated(seed, documents, principals)


def visible(claims: dict, document: Document) -> bool:
    """The decision table: same tenant, clearance at least the classification, an allowed department if any are named, every required
    project. Administrators search nothing."""
    if claims.get("scope") == "admin" or claims.get("tenant_id") != document.tenant:
        return False
    if CLEARANCE[document.classification] > CLEARANCE.get(claims.get("clearance", "public"), 0):
        return False
    if document.allowed_departments and claims.get("department") not in document.allowed_departments:
        return False
    return set(document.required_projects) <= set(claims.get("projects") or [])


def write(generated: Generated, out: Path) -> None:
    """Writes `corpus/<tenant>/<key>.md`, `manifests/<tenant>.yaml` and `visibility.yaml` in the layout of `data/`."""
    for tenant in sorted({d.tenant for d in generated.documents}):
        documents = [d for d in generated.documents if d.tenant == tenant]
        (out / "corpus" / tenant).mkdir(parents=True, exist_ok=True)
        entries = []
        for d in documents:
            file = f"corpus/{tenant}/{d.key}.md"
            (out / file).write_text(d.content, encoding="utf-8")
            entry: dict = {"key": d.key, "title": d.title, "file": file, "classification": d.classification}
            if d.allowed_departments:
                entry["allowed_departments"] = d.allowed_departments
            if d.required_projects:
                entry["required_projects"] = d.required_projects
            entries.append(entry)
        (out / "manifests").mkdir(parents=True, exist_ok=True)
        header = f"# Generated by `ga-eval distractors --seed {generated.seed}`; distractors only, never evidence.\n"
        (out / "manifests" / f"{tenant}.yaml").write_text(
            header + yaml.safe_dump({"tenant": tenant, "documents": entries}, sort_keys=False, width=150), encoding="utf-8"
        )
    visibility = {
        name: sorted(d.key for d in generated.documents if visible(claims, d))
        for name, claims in sorted(generated.principals.items())
        if claims.get("scope") != "admin"
    }
    (out / "visibility.yaml").write_text(yaml.safe_dump({"principals": visibility}, sort_keys=True, width=150), encoding="utf-8")


def _document(rng: random.Random, number: int) -> Document:
    tenant = "external" if rng.random() < EXTERNAL_SHARE else "northstar"
    classification = rng.choices([c for c, _ in CLASSIFICATIONS], weights=[w for _, w in CLASSIFICATIONS])[0]
    departments = [rng.choice(DEPARTMENTS)] if rng.random() < DEPARTMENT_SHARE else []
    projects: list[str] = []
    if tenant == "northstar":
        draw = rng.random()
        cumulative = 0.0
        for project, share in PROJECT_SHARES:
            cumulative += share
            if draw < cumulative:
                projects = [project]
                break
    system = rng.choice(SYSTEMS)
    title = f"{system.title()} {rng.choice(DOCUMENT_TYPES)} {number:05d}"
    sections = [f"# {title}\n"]
    for heading in rng.sample(HEADINGS, rng.randint(3, 7)):
        # Most sections are short; about one in ten is long enough that chunker release 4 has to split it
        words = rng.randint(420, 700) if rng.random() < 0.1 else rng.randint(40, 200)
        sections.append(f"## {heading}\n\n{_paragraphs(rng, system, words)}\n")
    return Document(f"{PREFIX}{number:05d}", tenant, title, "\n".join(sections), classification, departments, projects)


def _paragraphs(rng: random.Random, system: str, words: int) -> str:
    paragraphs: list[str] = []
    sentences: list[str] = []
    count = 0
    while count < words:
        sentence = (
            f"{rng.choice(TEAMS).capitalize()} {rng.choice(ACTIONS)} on the {system} {rng.choice(CONDITIONS)}, which {rng.choice(OUTCOMES)}."
        ).replace("{n}", str(rng.randint(2, 90)))
        sentences.append(sentence)
        count += len(sentence.split())
        if len(sentences) == 5:
            paragraphs.append(" ".join(sentences))
            sentences = []
    if sentences:
        paragraphs.append(" ".join(sentences))
    return "\n\n".join(paragraphs)
