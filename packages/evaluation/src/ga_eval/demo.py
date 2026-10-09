"""A guided tour of the running stack: `./scripts/demo`.

Each step prints what it is about to show, makes real API calls as demo principals, and checks the claim it makes. If a claim does not
hold, for example a hidden document becomes visible, the tour stops with an error, so it doubles as an end-to-end check.
"""

from collections.abc import Callable

from ga_eval import tokens
from ga_eval.client import ApiClient
from ga_eval.dataset import Dataset

WIDTH = 100


class DemoFailedError(RuntimeError):
    """The stack did not behave the way the tour says it does."""


class Tour:
    def __init__(self, dataset: Dataset, client: ApiClient, pause: Callable[[], None]) -> None:
        self._dataset = dataset
        self._client = client
        self._pause = pause
        self._step = 0

    def run(self) -> None:
        self._tenants()
        self._clearance()
        self._hidden_documents()
        self._answers()
        self._trace()
        print("\nNext: ./scripts/benchmark evaluates every strategy on 128 cases and fails on a single unauthorized result.")

    def _token(self, principal: str) -> str:
        return tokens.mint(self._dataset.root, principal, self._dataset.principals[principal], scope="query")

    def _heading(self, title: str, explanation: str) -> None:
        if self._step:
            self._pause()
        self._step += 1
        print(f"\n{'─' * WIDTH}\n{self._step}. {title}\n{explanation}\n")

    def _describe(self, principal: str) -> str:
        claims = self._dataset.principals[principal]
        attributes = [f"tenant {claims['tenant_id']}"] + [f"{key} {claims[key]}" for key in ("clearance", "department") if key in claims]
        return f"{principal} ({', '.join(attributes)})"

    def _search(self, principal: str, question: str) -> list[str]:
        response = self._client.search(self._token(principal), question, "dense-only", 2)
        print(f"  {self._describe(principal)}")
        for result in response["results"]:
            print(f"    {result['rank']}. {result['documentKey']} › {result['sectionPath']}")
            print(f"       {_shorten(result['text'])}")
        return [result["documentKey"] for result in response["results"]]

    def _tenants(self) -> None:
        question = "How many paid volunteer days do EU employees receive?"
        self._heading("The same question from two tenants", f'Only the token differs. Question: "{question}"')
        mine = self._search("alice-engineer", question)
        theirs = self._search("mallory-outsider", question)
        _expect(not set(mine) & set(theirs) and mine and theirs, "the two tenants share a result")
        print("\n  The outsider gets their own tenant's documents: no error, no hit count, no Northstar title.")

    def _clearance(self) -> None:
        question = "How much on-call allowance do engineers at staff level receive?"
        self._heading("The same question with two clearances", f'Same tenant. Question: "{question}"')
        alice = self._search("alice-engineer", question)
        carol = self._search("carol-manager", question)
        _expect("hr-compensation-bands" not in alice, "a confidential document was returned to a principal with internal clearance")
        _expect(carol[:1] == ["hr-compensation-bands"], "the cleared principal did not get the confidential document first")
        print("\n  The confidential passage is the first result for Carol and is not a row in Alice's result set.")

    def _hidden_documents(self) -> None:
        self._heading(
            "A hidden document looks exactly like a missing one",
            "Alice asks for a document she may not read, then for one that does not exist.",
        )
        alice, carol = self._token("alice-engineer"), self._token("carol-manager")
        hidden_status, hidden = self._client.get(alice, "/api/v1/documents/hr-compensation-bands")
        missing_status, missing = self._client.get(alice, "/api/v1/documents/no-such-document")
        allowed_status, allowed = self._client.get(carol, "/api/v1/documents/hr-compensation-bands")
        print(f"  alice-engineer  GET /api/v1/documents/hr-compensation-bands  -> {hidden_status} {hidden.get('detail')}")
        print(f"  alice-engineer  GET /api/v1/documents/no-such-document       -> {missing_status} {missing.get('detail')}")
        print(f"  carol-manager   GET /api/v1/documents/hr-compensation-bands  -> {allowed_status} {allowed.get('title')}")
        _expect(hidden_status == missing_status == 404, "a hidden document did not answer 404")
        _expect(_without(hidden, "instance") == _without(missing, "instance"), "the two refusals differ")
        _expect(allowed_status == 200, "the cleared principal cannot read the document")
        print("\n  Not 403: a 403 would confirm that the document exists.")

    def _answers(self) -> None:
        question = "How many paid volunteer days do EU employees receive?"
        self._heading("An answer cites the evidence it stands on", f'Question to /api/v1/query: "{question}"')
        answer = self._client.query(self._token("alice-engineer"), question)
        print(f"  status: {answer['status']}   chat model: {answer['chatModel'] or 'none configured'}")
        for statement in answer["statements"]:
            print(f"    {statement['text']}  [{', '.join(statement['citations'])}]")
        for evidence in answer["evidence"][:3]:
            print(f"    {evidence['id']}  {evidence['documentKey']} › {evidence['sectionPath']}")
        visible = self._dataset.visibility["alice-engineer"]
        _expect(all(evidence["documentKey"] in visible for evidence in answer["evidence"]), "the evidence contains a document Alice may not read")
        if answer["chatModel"] is None:
            print("\n  No chat model is configured, so the evidence is returned alone. Set GA_CHAT_BASE_URL and GA_CHAT_MODEL to get")
            print("  statements. A chat model only ever sees these passages, and a statement survives only if it cites one of them.")
            return
        print("\n  The chat model saw only the passages above. A statement survives only if every citation names one of them.")
        refused = "How often is the root key ceremony repeated?"
        print(f'\n  A question whose answer Alice may not read: "{refused}"')
        answer = self._client.query(self._token("alice-engineer"), refused)
        print(f"  status: {answer['status']}")
        for statement in answer["statements"]:
            print(f"    {statement['text']}  [{', '.join(statement['citations'])}]")
        _expect(all(evidence["documentKey"] in visible for evidence in answer["evidence"]), "the evidence contains a document Alice may not read")
        if answer["status"] == "no_answer":
            print("  The answer is in a restricted document, so the correct response is no_answer, and it carries no evidence.")
        else:
            print("  The correct response is no_answer: the answer is in a restricted document that was never in the prompt. The model")
            print("  answered from a look-alike passage instead. Nothing leaked, but the answer is wrong; the published run measures")
            print("  how often this happens (docs/evaluation/m3-answers-analysis.md).")

    def _trace(self) -> None:
        self._heading("One id explains a request", "The trace id of a search is in the response, the audit rows, the execution record and the logs.")
        alice, bob = self._token("alice-engineer"), self._token("bob-support")
        response = self._client.search(alice, "When must an on-call engineer acknowledge a page?", "hybrid-rrf-rerank", 3)
        status, record = self._client.get(alice, f"/api/v1/query-executions/{response['executionId']}")
        _expect(status == 200 and record["traceId"] == response["traceId"], "the execution record does not carry the trace id of its search")
        print(f"  trace id        {response['traceId']}")
        print(f"  plan hash       {record['planHash']}   policy {record['policyVersion']}")
        print(f"  embedding model {record['embeddingModel']}")
        print(f"  reranker        {record['rerankerModel'] or 'not used: ' + ', '.join(record['degraded'])}")
        print(f"  results         {record['resultCount']} in {record['totalMs']} ms")
        other_status, _ = self._client.get(bob, f"/api/v1/query-executions/{response['executionId']}")
        print(f"\n  bob-support asks for the same record -> {other_status}: an execution record exists only for its owner.")
        _expect(other_status == 404, "another principal can read the execution record")
        print("  Started with COMPOSE_PROFILES=observability, the same id opens the trace in Grafana at http://localhost:3000.")


def _expect(holds: bool, otherwise: str) -> None:
    if not holds:
        raise DemoFailedError(otherwise)


def _shorten(text: str) -> str:
    line = " ".join(text.split())
    return line if len(line) <= WIDTH - 10 else line[: WIDTH - 11] + "…"


def _without(body: dict, key: str) -> dict:
    return {name: value for name, value in body.items() if name != key}
