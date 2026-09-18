from pathlib import Path

import pytest

from ga_eval import dataset as ds

DATA = Path(__file__).resolve().parents[3] / "data"


def test_normalization_matches_the_control_plane_chunker():
    # Same inputs and expectations as MarkdownChunkerTest.normalizationIsStableForHashing
    assert ds.normalize("﻿  text\r\n\r\n") == "text\n"
    assert ds.normalize("a\rb\r\nc") == "a\nb\nc\n"


def test_the_committed_dataset_is_valid():
    assert ds.validate(ds.load(DATA)) == []


@pytest.fixture
def data() -> ds.Dataset:
    return ds.load(DATA)


def with_case(data: ds.Dataset, **changes) -> ds.Dataset:
    case = data.cases[0].model_copy(update=changes)
    return ds.Dataset(data.root, data.version, data.manifests, data.texts, data.tenant_of, [case], data.visibility, data.principals)


def test_rejects_quotes_that_are_not_in_the_document(data):
    evidence = data.cases[0].evidence[0].model_copy(update={"quote": "This sentence is not in the policy."})

    assert "quote must occur exactly once" in ds.validate(with_case(data, evidence=[evidence]))[0]


def test_rejects_unauthorized_documents_that_the_principal_can_see(data):
    problems = ds.validate(with_case(data, unauthorized_documents=["hr-travel-policy"]))

    assert problems == [f"case {data.cases[0].id}: hr-travel-policy is listed as unauthorized but is visible to alice-engineer"]


def test_rejects_visibility_labels_that_cross_tenants(data):
    visibility = dict(data.visibility) | {"mallory-outsider": {"it-faq", "hr-travel-policy"}}
    broken = ds.Dataset(data.root, data.version, data.manifests, data.texts, data.tenant_of, data.cases, visibility, data.principals)

    assert "visibility mallory-outsider: hr-travel-policy belongs to another tenant" in ds.validate(broken)


def test_evidence_spans_point_at_the_quote(data):
    evidence = data.cases[0].evidence[0]
    span = data.span(evidence)

    assert data.texts[evidence.document][span.start : span.end] == evidence.quote
