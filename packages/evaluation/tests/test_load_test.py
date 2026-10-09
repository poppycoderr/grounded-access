import itertools
from pathlib import Path

import httpx

from ga_eval import dataset as ds
from ga_eval import load

DATA = Path(__file__).resolve().parents[3] / "data"


class ScriptedClient:
    """Answers every search with one document chosen by the test, a fresh trace id, and optionally a refusal for one strategy."""

    def __init__(self, document: str, refuse: str | None = None) -> None:
        self._document = document
        self._refuse = refuse
        self._ids = itertools.count()

    def search(self, token: str, query: str, strategy: str, k: int, as_of: str | None = None, region: str | None = None) -> dict:
        if strategy == self._refuse:
            request = httpx.Request("POST", "http://test/api/v1/retrieval/search")
            raise httpx.HTTPStatusError("503", request=request, response=httpx.Response(503, request=request))
        degraded = ["rerank_unavailable"] if strategy == "hybrid-rrf-rerank" else []
        results = [{"documentKey": self._document, "versionNo": 1, "charStart": 0, "charEnd": 1, "rank": 1}]
        return {"results": results, "degraded": degraded, "traceId": f"{next(self._ids):032x}"}


def test_counts_refused_and_degraded_requests_per_strategy():
    data = ds.load(DATA)

    info = load.run(
        data, ScriptedClient("eng-incident-postmortem-2025-03", refuse="dense-only"), ["sparse-only", "dense-only", "hybrid-rrf-rerank"], 4, 30, 5
    )["run"]

    assert info["requests"] == 30
    assert info["failed"] == 10
    assert info["degraded"] == 10
    assert info["repeated_trace_ids"] == 0
    assert info["strategies"]["dense-only"]["statuses"] == {"503": 10}
    assert info["strategies"]["hybrid-rrf-rerank"]["degraded"] == {"rerank_unavailable": 10}
    assert info["strategies"]["sparse-only"]["statuses"] == {"200": 10}


def test_a_result_outside_the_asking_principals_visible_set_is_a_violation():
    data = ds.load(DATA)
    hidden_from_someone = next(d for d in data.current_version if any(d not in visible for visible in data.visibility.values()))

    output = load.run(data, ScriptedClient(hidden_from_someone), ["sparse-only"], 8, len(data.cases), 5)

    wrong = [r for r in output["requests"] if r["violations"]]
    assert output["run"]["security_violations"] == len(wrong) > 0
    assert all(hidden_from_someone not in data.visibility[r["principal"]] for r in wrong)
    assert "unauthorized results" in load.render(output)


def test_percentiles_of_few_samples_do_not_run_off_the_end():
    assert load.percentile([1.0, 2.0, 3.0], 0.95) == 3.0
    assert load.percentile([], 0.95) is None
