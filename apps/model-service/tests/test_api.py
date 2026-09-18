import json
from pathlib import Path

from model_service.contract import openapi_document

CONTRACT = Path(__file__).resolve().parents[3] / "packages" / "contracts" / "model-service.openapi.json"


def test_embed_returns_one_vector_per_text_with_model_metadata(client, embedder):
    response = client.post("/v1/embed", json={"input_type": "passage", "texts": ["abc", "de"]})

    assert response.status_code == 200
    assert response.json() == {"model": "fake", "revision": "r1", "dimensions": 2, "vectors": [[3.0, 1.0], [2.0, 1.0]]}
    assert embedder.calls == [(["abc", "de"], "passage")]


def test_embed_passes_the_input_type_to_the_model(client, embedder):
    client.post("/v1/embed", json={"input_type": "query", "texts": ["abc"]})

    assert embedder.calls == [(["abc"], "query")]


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


def test_models_lists_the_loaded_embedding_model(client):
    assert client.get("/v1/models").json()["models"][0]["name"] == "fake"


def test_committed_contract_matches_the_implementation():
    committed = json.loads(CONTRACT.read_text())

    assert committed == openapi_document(), "regenerate with: uv run python -m model_service.contract > packages/contracts/model-service.openapi.json"
