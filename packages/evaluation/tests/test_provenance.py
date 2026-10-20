"""A run names what it measured, refuses to be a result when that changed midway, and a comparison states what differed (ADR-0008)."""

import json
from dataclasses import replace
from pathlib import Path

import fake_system
import pytest

from ga_eval import answers, between, provenance, runner
from ga_eval import dataset as ds

ROOT = Path(__file__).resolve().parents[3]
DATA = ds.load(ROOT / "data")
CASE = next(c for c in DATA.cases if c.split == "test" and c.evidence and not c.out_of_scope_documents)


def chunk(key: str, version: int | None = None, start: int = 0) -> dict:
    return {
        "documentKey": key,
        "versionNo": version or DATA.current_version[key],
        "charStart": start,
        "charEnd": start + 1,
        "text": "",
        "chunkerVersion": "markdown/3",
    }


class ChangingClient:
    """Answers every search cleanly. `change` alters what the system reports from the given call of `system` or `list_chunks` on."""

    def __init__(self, change=None, on_call: int = 2, degraded_first: bool = False) -> None:
        self.change = change
        self.on_call = on_call
        self.degraded_first = degraded_first
        self.system_calls = 0
        self.listing_calls = 0
        self.searches = 0

    def system(self, token: str) -> dict:
        self.system_calls += 1
        described = fake_system.system()
        if self.change and self.system_calls >= self.on_call:
            self.change(described, None)
        return described

    def list_chunks(self, *args: object, **kwargs: object) -> tuple[str, list[dict]]:
        self.listing_calls += 1
        chunks = [chunk(key) for key in sorted(DATA.visibility[CASE.principal])]
        if self.change and self.listing_calls > 1:
            self.change(None, chunks)
        return "abac/1", chunks

    def search(self, *args: object) -> dict:
        self.searches += 1
        degraded = ["dense_unavailable"] if self.degraded_first and self.searches == 1 else []
        return {"policyVersion": "abac/1", "planHash": "plan", "plan": {}, "degraded": degraded, "results": []}

    def query(self, *args: object) -> dict:
        return {"status": "no_answer", "statements": [], "evidence": [], "degraded": [], "chatModel": "chat", "promptVersion": "answer-prompt/1"}


def run(client: ChangingClient) -> dict:
    return runner.run(replace(DATA, cases=[CASE]), client, ["hybrid-rrf"], 10, {"test"})


def test_a_run_records_the_system_it_measured_and_how():
    output = run(ChangingClient())

    manifest = output["run"]["manifest"]
    assert manifest["manifest_version"] == provenance.MANIFEST_VERSION
    assert manifest["server"] == fake_system.SYSTEM
    assert manifest["dataset"] == {"version": DATA.version, "digest": ds.digest(DATA)}
    assert manifest["corpus"]["chunks"] == len(DATA.visibility[CASE.principal])
    assert manifest["run"] == {
        "strategies": ["hybrid-rrf"],
        "k": 10,
        "splits": ["test"],
        "attempt_limit": runner.ATTEMPTS,
        "warmup_requests": 0,
        "metrics_version": "2",
    }
    assert set(manifest["evaluator"]) == {"git_sha", "uncommitted_changes", "ga_eval_version", "cpu", "platform"}
    report = runner.render(output)
    assert "server commit `abc1234`" in report
    assert "index `BAAI/bge-small-en-v1.5@rev1`" in report
    assert "0 warmup requests" in report


def test_every_attempt_of_a_request_is_recorded():
    output = run(ChangingClient(degraded_first=True))

    attempts = output["cases"][0]["attempts"]
    assert [a["degraded"] for a in attempts] == [["dense_unavailable"], []]
    assert all(a["latency_ms"] >= 0 for a in attempts)


def _commit(system, chunks):
    if system:
        system["build"]["commit"] = "def5678"


def _revision(system, chunks):
    if system:
        system["indexProfile"]["embeddingRevision"] = "rev9"


def _reranker(system, chunks):
    if system:
        system["reranker"]["revision"] = None


def _corpus(system, chunks):
    if chunks is not None:
        chunks[0]["versionNo"] += 1


@pytest.mark.parametrize(
    ("change", "named"),
    [(_commit, "server commit"), (_revision, "index profile"), (_reranker, "reranker"), (_corpus, "corpus")],
    ids=["server commit", "index profile", "reranker", "corpus"],
)
def test_a_system_that_changed_during_the_run_makes_it_invalid(change, named):
    with pytest.raises(runner.DriftError, match=named) as drifted:
        run(ChangingClient(change))

    info = drifted.value.output["run"]
    assert info["status"] == "invalid"
    assert info["invalid_reason"].startswith(f"drift: {named}")
    assert info["drift"]["changed"] == [named]


def test_the_corpus_digest_ignores_order_but_not_who_lists_what():
    one = {"alice": ("abac/1", [chunk("hr-volunteer-policy", 1), chunk("hr-volunteer-policy", 1, start=5)])}
    reordered = {"alice": ("abac/1", list(reversed(one["alice"][1])))}
    other_principal = {"bob": one["alice"]}
    moved = {"alice": ("abac/1", [chunk("hr-volunteer-policy", 1), chunk("hr-volunteer-policy", 1, start=6)])}

    assert provenance.corpus_digest(one) == provenance.corpus_digest(reordered)
    assert provenance.corpus_digest(one) != provenance.corpus_digest(other_principal)
    assert provenance.corpus_digest(one) != provenance.corpus_digest(moved)


def test_an_answer_run_records_the_system_and_is_invalid_when_it_changed():
    output = answers.run(replace(DATA, cases=[CASE]), ChangingClient(), {"test"})
    assert output["run"]["manifest"]["server"] == fake_system.SYSTEM
    assert output["run"]["manifest"]["run"]["chat_models"] == ["chat"]

    with pytest.raises(runner.DriftError, match="index profile") as drifted:
        answers.run(replace(DATA, cases=[CASE]), ChangingClient(_revision), {"test"})
    assert drifted.value.output["run"]["status"] == "invalid"
    assert "This run is invalid" in answers.render(drifted.value.output)


def write(directory: Path, output: dict) -> Path:
    runner.write(output, directory)
    return directory


def with_latency(output: dict, cpu: str) -> dict:
    output["run"]["manifest"]["server"]["host"]["cpu"] = cpu
    return output


def test_a_comparison_lists_what_differs_between_the_manifests(tmp_path):
    before = write(tmp_path / "before", run(ChangingClient()))
    changed = run(ChangingClient())
    changed["run"]["manifest"]["server"]["indexProfile"]["embeddingContext"] = False
    after = write(tmp_path / "after", changed)

    comparison = between.compare(before, after, "test")

    fields = [d["field"] for d in comparison["differences"]]
    assert "server.indexProfile.embeddingContext" in fields
    assert {"field": "server.indexProfile.embeddingContext", "before": True, "after": False} in comparison["differences"]
    assert "`server.indexProfile.embeddingContext`: `True` → `False`" in between.render(comparison)


def test_latency_is_compared_only_when_both_runs_were_served_by_the_same_cpu(tmp_path):
    first = write(tmp_path / "first", with_latency(run(ChangingClient()), "CPU A"))
    same = write(tmp_path / "same", with_latency(run(ChangingClient()), "CPU A"))
    other = write(tmp_path / "other", with_latency(run(ChangingClient()), "CPU B"))

    compared = between.compare(first, same, "test")["latency"]
    withheld = between.compare(first, other, "test")

    assert compared["compared"] and compared["cpu"] == "CPU A" and "hybrid-rrf" in compared["strategies"]
    assert withheld["latency"] == {"compared": False, "reason": "the runs were served by different CPUs (CPU A and CPU B)"}
    assert "Not compared: the runs were served by different CPUs" in between.render(withheld)


def test_a_run_without_a_manifest_is_compared_but_named(tmp_path):
    legacy_dir = tmp_path / "legacy"
    legacy_dir.mkdir()
    current = write(tmp_path / "current", run(ChangingClient()))
    info = json.loads((current / "run.json").read_text())
    del info["manifest"]
    (legacy_dir / "run.json").write_text(json.dumps(info))
    (legacy_dir / "cases.jsonl").write_text((current / "cases.jsonl").read_text())

    comparison = between.compare(legacy_dir, current, "test")

    assert "differences" not in comparison
    assert "The before run has no manifest" in between.render(comparison)
