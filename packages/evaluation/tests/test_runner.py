from pathlib import Path

import pytest

from ga_eval import dataset as ds
from ga_eval import runner

DATA = Path(__file__).resolve().parents[3] / "data"


class ListingOnlyClient:
    """Serves chunk listings with the given chunker versions; a run must stop before it searches."""

    def __init__(self, versions: list[str]) -> None:
        self._versions = versions

    def list_chunks(self, token: str) -> tuple[str, list[dict]]:
        return "abac/1", [
            {"documentKey": "hr-volunteer-policy", "versionNo": 1, "charStart": 0, "charEnd": 1, "text": "", "chunkerVersion": v}
            for v in self._versions
        ]

    def search(self, *args: object) -> dict:
        raise AssertionError("searched a corpus with mixed chunker releases")


def test_chunker_release_ignores_the_format():
    assert runner.chunker_release("markdown/2") == runner.chunker_release("text/2") == "2"
    assert runner.chunker_release("markdown-headings/1") == "1"


def test_refuses_to_run_on_a_partially_re_indexed_corpus():
    with pytest.raises(runner.MixedChunkerError, match="markdown-headings/1, markdown/2"):
        runner.run(ds.load(DATA), ListingOnlyClient(["markdown/2", "markdown-headings/1"]), ["sparse-only"], 10, {"test"})


class DegradedClient(ListingOnlyClient):
    def search(self, *args: object) -> dict:
        return {"policyVersion": "tenant-only/1", "planHash": "abc", "plan": {}, "degraded": ["dense_unavailable"], "results": []}


def test_refuses_results_the_system_produced_without_its_full_plan(monkeypatch):
    monkeypatch.setattr(runner, "check_visibility", lambda dataset, listings: {})
    with pytest.raises(runner.DegradedRunError, match="dense_unavailable"):
        runner.run(ds.load(DATA), DegradedClient(["markdown/2"]), ["hybrid-rrf"], 10, {"test"})


class Listing:
    """Returns a fixed chunk listing to every principal."""

    def __init__(self, documents: dict[str, int]) -> None:
        self._chunks = [
            {"documentKey": key, "versionNo": version, "charStart": 0, "charEnd": 1, "text": "", "chunkerVersion": "markdown/2"}
            for key, version in documents.items()
        ]

    def listings(self, principal: str) -> dict[str, tuple[str, list[dict]]]:
        return {principal: ("abac/1", self._chunks)}


def test_listing_a_document_outside_the_visible_set_or_a_replaced_version_is_a_violation():
    data = ds.load(DATA)
    listed = {key: 1 for key in data.visibility["dave-contractor"]} | {"hr-travel-policy": 1, "sales-product-overview": 3}

    check = runner.check_visibility(data, Listing(listed).listings("dave-contractor"))

    assert check["dave-contractor"]["violations"] == ["hr-travel-policy", "sales-product-overview@v3"]


def test_a_principal_that_cannot_list_a_visible_document_aborts_the_run():
    data = ds.load(DATA)

    with pytest.raises(runner.VisibilityMismatchError, match="dave-contractor cannot list hr-volunteer-policy"):
        runner.check_visibility(data, Listing({"sales-product-overview": 1}).listings("dave-contractor"))


def test_a_run_records_the_cpu_that_produced_it():
    assert runner.cpu_model().strip()
