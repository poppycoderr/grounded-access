"""Prints the OpenAPI contract; the committed copy lives in packages/contracts/model-service.openapi.json."""

import json

from model_service.app import create_app
from model_service.schemas import ModelInfo
from model_service.settings import Settings


class _ContractOnlyEmbedder:
    info = ModelInfo(name="contract", task="embedding", revision="contract", dimensions=0, query_prefix="", license="none")

    def embed(self, texts: list[str], input_type: str) -> list[list[float]]:
        return []


def openapi_document() -> dict:
    return create_app(_ContractOnlyEmbedder(), Settings.from_env()).openapi()


if __name__ == "__main__":
    print(json.dumps(openapi_document(), indent=2, sort_keys=True))
