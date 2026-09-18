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


class ModelInfo(BaseModel):
    name: str
    task: Literal["embedding"]
    revision: str
    dimensions: int
    query_prefix: str
    license: str


class ModelsResponse(BaseModel):
    models: list[ModelInfo]


class ErrorResponse(BaseModel):
    code: str
    message: str
