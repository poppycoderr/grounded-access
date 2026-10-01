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
        return "tenant-only/1", [{"chunkerVersion": v} for v in self._versions]

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


def test_refuses_results_the_system_produced_without_its_full_plan():
    with pytest.raises(runner.DegradedRunError, match="dense_unavailable"):
        runner.run(ds.load(DATA), DegradedClient(["markdown/2"]), ["hybrid-rrf"], 10, {"test"})
