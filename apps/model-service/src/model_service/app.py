"""HTTP API of the model service. It holds no identities and never sees tenant or principal data."""

import uuid
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse

from model_service.embedding import Embedder, FastEmbedEmbedder
from model_service.reranking import FastEmbedReranker, Reranker
from model_service.schemas import (
    EmbedRequest,
    EmbedResponse,
    ErrorResponse,
    ModelsResponse,
    PassageScore,
    RerankRequest,
    RerankResponse,
)
from model_service.settings import Settings

REQUEST_ID_HEADER = "x-request-id"


class ModelServiceError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def create_app(embedder: Embedder, reranker: Reranker, settings: Settings) -> FastAPI:
    app = FastAPI(title="Grounded Access model service", version="1.0.0")

    @app.middleware("http")
    async def request_id(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
        response = await call_next(request)
        response.headers[REQUEST_ID_HEADER] = request.headers.get(REQUEST_ID_HEADER) or uuid.uuid4().hex
        return response

    @app.exception_handler(ModelServiceError)
    async def service_error(_: Request, error: ModelServiceError) -> JSONResponse:
        return JSONResponse(status_code=error.status, content=ErrorResponse(code=error.code, message=error.message).model_dump())

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/v1/models")
    def models() -> ModelsResponse:
        return ModelsResponse(models=[embedder.info, reranker.info])

    @app.post("/v1/embed", responses={422: {"model": ErrorResponse}})
    def embed(request: EmbedRequest) -> EmbedResponse:
        info = embedder.info
        if request.model is not None and request.model != info.name:
            raise ModelServiceError(422, "MODEL_MISMATCH", f"requested {request.model}, loaded {info.name}")
        if len(request.texts) > settings.max_texts_per_request:
            raise ModelServiceError(422, "TOO_MANY_TEXTS", f"at most {settings.max_texts_per_request} texts per request")
        if any(len(t) > settings.max_chars_per_text for t in request.texts):
            raise ModelServiceError(422, "TEXT_TOO_LONG", f"each text must be at most {settings.max_chars_per_text} characters")
        vectors = embedder.embed(request.texts, request.input_type)
        return EmbedResponse(model=info.name, revision=info.revision, dimensions=info.dimensions, vectors=vectors)

    @app.post("/v1/rerank", responses={422: {"model": ErrorResponse}})
    def rerank(request: RerankRequest) -> RerankResponse:
        """Scores each passage against the query. The service does not sort or drop passages: ordering and cut-off belong to the caller,
        which also decides what happens when this call fails."""
        info = reranker.info
        if request.model is not None and request.model != info.name:
            raise ModelServiceError(422, "MODEL_MISMATCH", f"requested {request.model}, loaded {info.name}")
        if len(request.passages) > settings.max_passages_per_request:
            raise ModelServiceError(422, "TOO_MANY_PASSAGES", f"at most {settings.max_passages_per_request} passages per request")
        if len(request.query) > settings.max_chars_per_text or any(len(p.text) > settings.max_chars_per_text for p in request.passages):
            raise ModelServiceError(422, "TEXT_TOO_LONG", f"the query and each passage must be at most {settings.max_chars_per_text} characters")
        ids = [p.id for p in request.passages]
        if len(set(ids)) != len(ids):
            raise ModelServiceError(422, "DUPLICATE_PASSAGE_ID", "passage ids must be unique within a request")
        scores = reranker.score(request.query, [p.text for p in request.passages])
        return RerankResponse(
            model=info.name,
            revision=info.revision,
            scores=[PassageScore(id=passage_id, score=score) for passage_id, score in zip(ids, scores, strict=True)],
        )

    return app


def app() -> FastAPI:
    """Factory for uvicorn: `uvicorn model_service.app:app --factory`."""
    settings = Settings.from_env()
    return create_app(
        FastEmbedEmbedder(settings.embedding_model, settings.model_cache_dir),
        FastEmbedReranker(settings.reranker_model, settings.model_cache_dir),
        settings,
    )
