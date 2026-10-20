import json
from pathlib import Path

from model_service.contract import openapi_document

CONTRACT = Path(__file__).resolve().parents[3] / "packages" / "contracts" / "model-service.openapi.json"


def test_embed_returns_one_vector_per_text_with_model_metadata(client, embedder):
    response = client.post("/v1/embed", json={"input_type": "passage", "texts": ["abc", "de"]})

    assert response.status_code == 200
    assert response.json() == {"model": "fake", "revision": "r1", "dimensions": 2, "prefix": "", "vectors": [[3.0, 1.0], [2.0, 1.0]]}
    assert embedder.calls == [(["abc", "de"], "passage")]


def test_embed_passes_the_input_type_to_the_model_and_reports_its_prefix(client, embedder):
    response = client.post("/v1/embed", json={"input_type": "query", "texts": ["abc"]})

    assert embedder.calls == [(["abc"], "query")]
    assert response.json()["prefix"] == "q: "


def test_embed_rejects_a_model_other_than_the_loaded_one(client, embedder):
    response = client.post("/v1/embed", json={"model": "other", "input_type": "query", "texts": ["abc"]})

    assert response.status_code == 422
    assert response.json()["code"] == "MODEL_MISMATCH"
    assert embedder.calls == []


def test_embed_limits_batch_size_and_text_length(client):
    too_many = client.post("/v1/embed", json={"input_type": "passage", "texts": ["a", "b", "c", "d"]})
    too_long = client.post("/v1/embed", json={"input_type": "passage", "texts": ["x" * 21]})

    assert (too_many.status_code, too_many.json()["code"]) == (422, "TOO_MANY_TEXTS")
    assert (too_long.status_code, too_long.json()["code"]) == (422, "TEXT_TOO_LONG")


def test_embed_rejects_empty_input_and_unknown_fields(client):
    assert client.post("/v1/embed", json={"input_type": "passage", "texts": []}).status_code == 422
    assert client.post("/v1/embed", json={"input_type": "passage", "texts": ["a"], "tenant_id": "t"}).status_code == 422


def test_request_id_is_echoed_or_generated(client):
    echoed = client.get("/healthz", headers={"x-request-id": "abc123"})
    generated = client.get("/healthz")

    assert echoed.headers["x-request-id"] == "abc123"
    assert len(generated.headers["x-request-id"]) == 32


def test_models_lists_the_loaded_embedding_and_rerank_models(client):
    models = client.get("/v1/models").json()["models"]

    assert [(m["name"], m["task"], m["revision"]) for m in models] == [("fake", "embedding", "r1"), ("fake-reranker", "rerank", "r2")]
    assert models[1]["dimensions"] is None


def test_rerank_scores_every_passage_in_request_order_without_sorting(client, reranker):
    passages = [{"id": "c", "text": "paid leave"}, {"id": "a", "text": "volunteer days"}, {"id": "b", "text": "paid volunteer days"}]

    response = client.post("/v1/rerank", json={"query": "paid volunteer", "passages": passages})

    assert response.status_code == 200
    assert response.json() == {
        "model": "fake-reranker",
        "revision": "r2",
        "query_truncated": False,
        "scores": [{"id": "c", "score": 1.0}, {"id": "a", "score": 1.0}, {"id": "b", "score": 2.0}],
    }
    assert reranker.calls == [("paid volunteer", ["paid leave", "volunteer days", "paid volunteer days"])]


def test_rerank_rejects_a_model_mismatch_and_oversized_or_ambiguous_requests(client):
    one = [{"id": "a", "text": "abc"}]

    mismatch = client.post("/v1/rerank", json={"model": "other", "query": "q", "passages": one})
    too_many = client.post("/v1/rerank", json={"query": "q", "passages": [{"id": str(i), "text": "t"} for i in range(4)]})
    long_passage = client.post("/v1/rerank", json={"query": "q", "passages": [{"id": "a", "text": "x" * 21}]})
    long_query = client.post("/v1/rerank", json={"query": "q" * 21, "passages": one})
    duplicate = client.post("/v1/rerank", json={"query": "q", "passages": one + one})

    assert [r.json()["code"] for r in (mismatch, too_many, long_passage, long_query, duplicate)] == [
        "MODEL_MISMATCH",
        "TOO_MANY_PASSAGES",
        "TEXT_TOO_LONG",
        "TEXT_TOO_LONG",
        "DUPLICATE_PASSAGE_ID",
    ]
    assert all(r.status_code == 422 for r in (mismatch, too_many, long_passage, long_query, duplicate))


def test_rerank_rejects_empty_input_and_unknown_fields(client):
    assert client.post("/v1/rerank", json={"query": "q", "passages": []}).status_code == 422
    assert client.post("/v1/rerank", json={"query": "", "passages": [{"id": "a", "text": "t"}]}).status_code == 422
    assert client.post("/v1/rerank", json={"query": "q", "passages": [{"id": "a", "text": "t"}], "tenant_id": "t"}).status_code == 422
    assert client.post("/v1/rerank", json={"query": "q", "passages": [{"id": "a", "text": "t", "principal": "alice"}]}).status_code == 422


def test_committed_contract_matches_the_implementation():
    committed = json.loads(CONTRACT.read_text())

    assert committed == openapi_document(), "regenerate with: uv run python -m model_service.contract > packages/contracts/model-service.openapi.json"


def test_embed_refuses_a_text_over_the_token_limit_instead_of_cutting_it(client, embedder):
    # Eight tokens at most, [CLS] and [SEP] included; a query also carries its one-word prefix.
    fits = client.post("/v1/embed", json={"input_type": "passage", "texts": ["a b c d e f"]})
    over = client.post("/v1/embed", json={"input_type": "query", "texts": ["ok", "a b c d e f"]})

    assert fits.status_code == 200
    assert over.status_code == 422
    assert over.json() == {
        "code": "TEXT_TOO_LONG",
        "message": "text 1 has 9 tokens; the model accepts at most 8 per text, special tokens included",
    }
    assert embedder.calls == [(["a b c d e f"], "passage")]


def test_tokenize_counts_under_the_stricter_tokenizer_without_returning_text(client):
    response = client.post("/v1/tokenize", json={"texts": ["one two three", "a b c d e f g h i j"], "budget": 5})

    assert response.status_code == 200
    assert response.json() == {"max_tokens": 8, "counts": [5, 12], "fitting_chars": [13, 5]}
    assert client.post("/v1/tokenize", json={"texts": ["x"]}).json()["fitting_chars"] is None


def test_rerank_cuts_a_query_that_does_not_fit_with_the_longest_passage_and_says_so(client, reranker):
    passages = [{"id": "p", "text": "one two three four"}, {"id": "q", "text": "five"}]

    response = client.post("/v1/rerank", json={"query": "ab cd ef gh", "passages": passages})

    assert response.json()["query_truncated"] is True
    # Ten tokens: [CLS] query [SEP] four passage words [SEP] leaves three for the query.
    assert reranker.calls == [("ab cd ef", ["one two three four", "five"])]


def test_rerank_never_cuts_a_passage(client, reranker):
    response = client.post("/v1/rerank", json={"query": "q", "passages": [{"id": "p", "text": "a b c d e f g"}]})

    assert response.status_code == 422
    assert response.json()["code"] == "TEXT_TOO_LONG"
    assert reranker.calls == []
