from pathlib import Path

import pytest

from ga_eval import dataset as ds
from ga_eval import demo

DATA = Path(__file__).resolve().parents[3] / "data"


class TourClient:
    """Answers the calls of the tour the way the stack should, with switches for the ways it could go wrong."""

    def __init__(self, chat_model: str | None = None, leak: bool = False, forbidden_instead_of_missing: bool = False) -> None:
        self._chat_model = chat_model
        self._leak = leak
        self._forbidden = forbidden_instead_of_missing

    def search(self, token: str, query: str, strategy: str, k: int) -> dict:
        principal = _subject(token)
        if "volunteer" in query:
            keys = ["volunteer-handbook"] if principal == "mallory-outsider" else ["hr-volunteer-policy"]
        elif principal == "carol-manager" or self._leak:
            keys = ["hr-compensation-bands", "eng-oncall-handbook"]
        else:
            keys = ["eng-oncall-handbook"]
        results = [{"rank": i, "documentKey": key, "sectionPath": "Section", "text": "Text."} for i, key in enumerate(keys, start=1)]
        return {"results": results, "executionId": "e1", "traceId": "t" * 32}

    def get(self, token: str, path: str) -> tuple[int, dict]:
        principal = _subject(token)
        if path.startswith("/api/v1/query-executions/"):
            if principal != "alice-engineer":
                return 404, {}
            record = {"traceId": "t" * 32, "planHash": "abc", "policyVersion": "abac/1", "embeddingModel": "m@1", "rerankerModel": None}
            return 200, record | {"degraded": ["rerank_unavailable"], "resultCount": 3, "totalMs": 12}
        if path.endswith("hr-compensation-bands") and principal == "carol-manager":
            return 200, {"key": "hr-compensation-bands", "title": "Compensation Bands", "versionNo": 1}
        status = 403 if self._forbidden and path.endswith("hr-compensation-bands") else 404
        return status, {"detail": "No such document", "instance": path, "status": status}

    def query(self, token: str, question: str) -> dict:
        evidence = [{"id": "S1", "documentKey": "hr-volunteer-policy", "sectionPath": "European Union"}]
        if self._chat_model is None:
            return {"status": "evidence_only", "chatModel": None, "statements": [], "evidence": evidence}
        if "ceremony" in question:
            return {"status": "no_answer", "chatModel": self._chat_model, "statements": [], "evidence": []}
        statements = [{"text": "Two days.", "citations": ["S1"]}]
        return {"status": "answered", "chatModel": self._chat_model, "statements": statements, "evidence": evidence}


def _subject(token: str) -> str:
    import jwt

    return jwt.decode(token, options={"verify_signature": False})["sub"]


def test_the_tour_passes_when_the_stack_behaves(capsys):
    demo.Tour(ds.load(DATA), TourClient(), lambda: None).run()

    printed = capsys.readouterr().out
    assert "5. One id explains a request" in printed
    assert "No chat model is configured" in printed
    assert "not used: rerank_unavailable" in printed


def test_with_a_chat_model_the_tour_also_asks_a_question_that_must_be_refused(capsys):
    demo.Tour(ds.load(DATA), TourClient(chat_model="scripted"), lambda: None).run()

    printed = capsys.readouterr().out
    assert "Two days.  [S1]" in printed
    assert "the correct response is no_answer, and it carries no evidence" in printed


def test_the_tour_fails_when_a_confidential_document_reaches_the_wrong_principal():
    with pytest.raises(demo.DemoFailedError, match="confidential document was returned"):
        demo.Tour(ds.load(DATA), TourClient(leak=True), lambda: None).run()


def test_the_tour_fails_when_a_hidden_document_is_told_apart_from_a_missing_one():
    with pytest.raises(demo.DemoFailedError, match="hidden document did not answer 404"):
        demo.Tour(ds.load(DATA), TourClient(forbidden_instead_of_missing=True), lambda: None).run()
