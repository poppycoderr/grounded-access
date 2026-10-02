"""Evaluation dataset: manifests, documents, cases and visibility labels, plus validation against the corpus text."""

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field

from ga_eval import text


def normalize(text: str) -> str:
    """Must match DocumentChunker.normalize in the control plane: character offsets returned by the API point into this text."""
    unified = text.replace("\r\n", "\n").replace("\r", "\n")
    return unified.removeprefix("﻿").strip() + "\n"


class Evidence(BaseModel):
    model_config = ConfigDict(extra="forbid")

    document: str
    version: int = Field(ge=1)
    section: str
    quote: str = Field(min_length=10)


class Case(BaseModel):
    """One evaluation case. `as_of` and `region` are the scope of the request (ADR-0005): `as_of` filters current versions by their validity
    window and defaults to now; `region` defaults to the principal's region. `out_of_scope_documents` are documents the principal may read
    but that do not apply to this request; returning one is a scope failure, which is counted apart from security violations."""

    model_config = ConfigDict(extra="forbid")

    id: str
    split: Literal["dev", "test"]
    query: str
    principal: str
    evidence: list[Evidence]
    expected_facts: list[str]
    must_abstain: bool
    unauthorized_documents: list[str]
    hard_negative_documents: list[str]
    out_of_scope_documents: list[str] = []
    as_of: datetime | None = None
    region: str | None = None
    tags: list[str]


class ManifestVersion(BaseModel):
    """One version of a document: its file, who may read it, and where and when it applies. Missing labels mean public and unrestricted;
    missing scope means everywhere and always."""

    model_config = ConfigDict(extra="forbid")

    file: str
    classification: Literal["public", "internal", "confidential", "restricted"] = "public"
    allowed_departments: list[str] = []
    required_projects: list[str] = []
    applies_to_regions: list[str] = []
    valid_from: datetime | None = None
    valid_to: datetime | None = None


class ManifestDocument(ManifestVersion):
    """The current version of a document. `history` lists its earlier versions, oldest first."""

    key: str
    title: str
    history: list[ManifestVersion] = []

    def versions(self) -> list[ManifestVersion]:
        return [*self.history, self]


class Manifest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tenant: str
    documents: list[ManifestDocument]


@dataclass(frozen=True)
class Span:
    document: str
    version: int
    start: int
    end: int


@dataclass(frozen=True)
class Dataset:
    root: Path
    version: str
    manifests: list[Manifest]
    texts: dict[str, str]
    current_version: dict[str, int]
    tenant_of: dict[str, str]
    cases: list[Case]
    visibility: dict[str, set[str]]
    principals: dict[str, dict]

    def span(self, evidence: Evidence) -> Span:
        text = self.texts[evidence.document]
        start = text.find(evidence.quote)
        return Span(evidence.document, evidence.version, start, start + len(evidence.quote))


def load(root: Path, version: str = "v3") -> Dataset:
    manifests = [Manifest.model_validate(yaml.safe_load(p.read_text())) for p in sorted((root / "manifests").glob("*.yaml"))]
    texts: dict[str, str] = {}
    current_version: dict[str, int] = {}
    tenant_of: dict[str, str] = {}
    for manifest in manifests:
        for doc in manifest.documents:
            texts[doc.key] = normalize((root / doc.file).read_text(encoding="utf-8"))
            current_version[doc.key] = len(doc.versions())
            tenant_of[doc.key] = manifest.tenant
    eval_dir = root / "eval" / version
    cases = [Case.model_validate_json(line) for line in (eval_dir / "cases.jsonl").read_text().splitlines() if line.strip()]
    visibility = {p: set(docs) for p, docs in yaml.safe_load((eval_dir / "visibility.yaml").read_text())["principals"].items()}
    principals = yaml.safe_load((root / "principals.yaml").read_text())["principals"]
    return Dataset(root, version, manifests, texts, current_version, tenant_of, cases, visibility, principals)


LOW_OVERLAP_BELOW = 1 / 3
HIGH_OVERLAP_FROM = 2 / 3
MIN_LOW_OVERLAP_SHARE = 0.30
MIN_AUTHORIZATION_NEGATIVES = 25


def lexical_overlap(case: Case) -> float:
    """Share of the query's content tokens that also appear in its evidence. Low overlap means a paraphrase that keyword search cannot match."""
    query = set(text.tokens(case.query))
    evidence = {token for e in case.evidence for token in text.tokens(e.quote)}
    return len(query & evidence) / len(query) if query else 0.0


def overlap_bands(dataset: Dataset) -> dict[str, list[str]]:
    bands: dict[str, list[str]] = {"low": [], "mid": [], "high": []}
    for case in dataset.cases:
        if case.evidence:
            overlap = lexical_overlap(case)
            band = "low" if overlap < LOW_OVERLAP_BELOW else "high" if overlap >= HIGH_OVERLAP_FROM else "mid"
            bands[band].append(case.id)
    return bands


def authorization_negatives(dataset: Dataset) -> list[Case]:
    """Cases that try to reach a document their principal may not see, in another tenant or in their own."""
    return [c for c in dataset.cases if c.unauthorized_documents]


def validate(dataset: Dataset) -> list[str]:
    """Returns every problem found; an empty list means the dataset is consistent with the corpus."""
    problems: list[str] = []
    ids = [c.id for c in dataset.cases]
    problems += [f"duplicate case id {i}" for i in sorted({i for i in ids if ids.count(i) > 1})]
    for case in dataset.cases:
        where = f"case {case.id}"
        if case.principal not in dataset.principals:
            problems.append(f"{where}: unknown principal {case.principal}")
            continue
        if case.principal not in dataset.visibility:
            problems.append(f"{where}: principal {case.principal} has no visibility labels")
            continue
        visible = dataset.visibility[case.principal]
        if case.must_abstain == bool(case.evidence):
            problems.append(f"{where}: must_abstain cases have no evidence and answerable cases need evidence")
        for evidence in case.evidence:
            text = dataset.texts.get(evidence.document)
            if text is None:
                problems.append(f"{where}: unknown document {evidence.document}")
            elif text.count(evidence.quote) != 1:
                problems.append(f"{where}: quote must occur exactly once in {evidence.document}, found {text.count(evidence.quote)}")
            elif evidence.document not in visible:
                problems.append(f"{where}: evidence {evidence.document} is not visible to {case.principal}")
            elif evidence.version != dataset.current_version[evidence.document]:
                problems.append(
                    f"{where}: evidence cites version {evidence.version} of {evidence.document}, "
                    f"but only the current version {dataset.current_version[evidence.document]} can be retrieved"
                )
        for doc in case.unauthorized_documents:
            if doc not in dataset.texts:
                problems.append(f"{where}: unknown unauthorized document {doc}")
            elif doc in visible:
                problems.append(f"{where}: {doc} is listed as unauthorized but is visible to {case.principal}")
        problems += [f"{where}: unknown hard negative {doc}" for doc in case.hard_negative_documents if doc not in dataset.texts]
        for doc in case.out_of_scope_documents:
            if doc not in visible:
                problems.append(f"{where}: {doc} is listed as out of scope but is not visible to {case.principal}; that is an authorization case")
            elif any(e.document == doc for e in case.evidence):
                problems.append(f"{where}: {doc} is both evidence and out of scope")
    bands = overlap_bands(dataset)
    answerable = sum(len(ids) for ids in bands.values())
    if answerable and len(bands["low"]) / answerable < MIN_LOW_OVERLAP_SHARE:
        problems.append(
            f"only {len(bands['low'])} of {answerable} answerable cases are low-overlap paraphrases; at least {MIN_LOW_OVERLAP_SHARE:.0%} required"
        )
    negatives = authorization_negatives(dataset)
    if len(negatives) < MIN_AUTHORIZATION_NEGATIVES:
        problems.append(f"only {len(negatives)} authorization-negative cases; at least {MIN_AUTHORIZATION_NEGATIVES} required")
    for principal, docs in dataset.visibility.items():
        tenant = dataset.principals.get(principal, {}).get("tenant_id")
        problems += [f"visibility {principal}: {doc} belongs to another tenant" for doc in sorted(docs) if dataset.tenant_of.get(doc) != tenant]
    return problems
