"""Wire types of the model-service contract (packages/contracts/model-service.openapi.json)."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class EmbedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str | None = Field(default=None, description="Expected model name; the request fails if another model is loaded.")
    input_type: Literal["query", "passage"] = Field(description="Queries and passages may be encoded differently by the model.")
    texts: list[str] = Field(min_length=1)


class EmbedResponse(BaseModel):
    model: str
    revision: str
    dimensions: int
    vectors: list[list[float]]


class Passage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, max_length=200, description="Opaque to the service; returned with the score so the caller can match it.")
    text: str = Field(min_length=1)


class RerankRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str | None = Field(default=None, description="Expected model name; the request fails if another model is loaded.")
    query: str = Field(min_length=1)
    passages: list[Passage] = Field(min_length=1)


class PassageScore(BaseModel):
    id: str
    score: float = Field(description="Relevance of the passage to the query; higher is more relevant. Only comparable within one response.")


class RerankResponse(BaseModel):
    model: str
    revision: str
    scores: list[PassageScore] = Field(description="One entry per passage, in request order.")


class ModelInfo(BaseModel):
    name: str
    task: Literal["embedding", "rerank"]
    revision: str
    dimensions: int | None = Field(default=None, description="Vector size; embedding models only.")
    query_prefix: str | None = Field(default=None, description="Instruction prepended to queries; embedding models only.")
    license: str


class ModelsResponse(BaseModel):
    models: list[ModelInfo]


class ErrorResponse(BaseModel):
    code: str
    message: str
