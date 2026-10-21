"""HTTP API of the model service. It holds no identities and never sees tenant or principal data."""

import uuid
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Header, Request, Response
from fastapi.responses import JSONResponse

from model_service.admission import Admitted, Lane, Lanes, Refused
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
    TokenizeRequest,
    TokenizeResponse,
)
from model_service.settings import Settings
from model_service.tokens import TokenCounter

REQUEST_ID_HEADER = "x-request-id"


class ModelServiceError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


DEADLINE_HEADER = "x-deadline-ms"


def create_app(embedder: Embedder, reranker: Reranker, settings: Settings) -> FastAPI:
    app = FastAPI(title="Grounded Access model service", version="1.0.0")
    lanes = Lanes(
        query=Lane("query", settings.query_slots, settings.query_queue, settings.query_wait_seconds),
        passage=Lane("passage", settings.passage_slots, settings.passage_queue, settings.passage_wait_seconds),
        rerank=Lane("rerank", settings.rerank_slots, settings.rerank_queue, settings.rerank_wait_seconds),
    )

    @app.middleware("http")
    async def request_id(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
        response = await call_next(request)
        response.headers[REQUEST_ID_HEADER] = request.headers.get(REQUEST_ID_HEADER) or uuid.uuid4().hex
        return response

    @app.exception_handler(ModelServiceError)
    async def service_error(_: Request, error: ModelServiceError) -> JSONResponse:
        return JSONResponse(status_code=error.status, content=ErrorResponse(code=error.code, message=error.message).model_dump())

    @app.exception_handler(Refused)
    async def refused(_: Request, refusal: Refused) -> JSONResponse:
        return JSONResponse(
            status_code=503, content=ErrorResponse(code=refusal.code, message=refusal.message).model_dump(), headers={"Retry-After": "1"}
        )

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/v1/models")
    def models() -> ModelsResponse:
        return ModelsResponse(models=[embedder.info, reranker.info])

    @app.post("/v1/embed", responses={422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}})
    def embed(
        request: EmbedRequest,
        response: Response,
        x_deadline_ms: int | None = Header(default=None, description="Milliseconds the caller still waits; work not started by then is refused."),
    ) -> EmbedResponse:
        info = embedder.info
        if request.model is not None and request.model != info.name:
            raise ModelServiceError(422, "MODEL_MISMATCH", f"requested {request.model}, loaded {info.name}")
        if len(request.texts) > settings.max_texts_per_request:
            raise ModelServiceError(422, "TOO_MANY_TEXTS", f"at most {settings.max_texts_per_request} texts per request")
        if any(len(t) > settings.max_chars_per_text for t in request.texts):
            raise ModelServiceError(422, "TEXT_TOO_LONG", f"each text must be at most {settings.max_chars_per_text} characters")
        prefix = info.query_prefix if request.input_type == "query" else info.passage_prefix
        # Counted before embedding: the model would silently cut a longer input and represent only its beginning.
        limit = embedder.tokens.max_tokens
        over = [(i, n) for i, n in enumerate(embedder.tokens.count((prefix or "") + t) for t in request.texts) if n > limit]
        if over:
            listed = ", ".join(f"text {i} has {n}" for i, n in over[:5])
            raise ModelServiceError(422, "TEXT_TOO_LONG", f"{listed} tokens; the model accepts at most {limit} per text, special tokens included")
        with (lanes.query if request.input_type == "query" else lanes.passage).admit(x_deadline_ms) as admitted:
            vectors = embedder.embed(request.texts, request.input_type)
            _timing(response, admitted)
        return EmbedResponse(model=info.name, revision=info.revision, dimensions=info.dimensions, prefix=prefix or "", vectors=vectors)

    @app.post("/v1/tokenize", responses={422: {"model": ErrorResponse}})
    def tokenize(request: TokenizeRequest) -> TokenizeResponse:
        """Token counts as passages under both models' tokenizers, so the control plane can make chunks that fit both. No text, token or
        vector is returned."""
        if len(request.texts) > settings.max_texts_per_request:
            raise ModelServiceError(422, "TOO_MANY_TEXTS", f"at most {settings.max_texts_per_request} texts per request")
        if any(len(t) > settings.max_chars_per_text for t in request.texts):
            raise ModelServiceError(422, "TEXT_TOO_LONG", f"each text must be at most {settings.max_chars_per_text} characters")
        counters = (embedder.tokens, reranker.tokens)
        prefix = embedder.info.passage_prefix or ""
        return TokenizeResponse(
            max_tokens=min(c.max_tokens for c in counters),
            counts=[max(c.count(prefix + t) for c in counters) for t in request.texts],
            fitting_chars=None
            if request.budget is None
            else [max(0, min(c.fitting_chars(prefix + t, request.budget) for c in counters) - len(prefix)) for t in request.texts],
        )

    @app.post("/v1/rerank", responses={422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}})
    def rerank(
        request: RerankRequest,
        response: Response,
        x_deadline_ms: int | None = Header(default=None, description="Milliseconds the caller still waits; work not started by then is refused."),
    ) -> RerankResponse:
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
        query, truncated = _fit_query(reranker.tokens, request.query, [p.text for p in request.passages])
        with lanes.rerank.admit(x_deadline_ms) as admitted:
            scores = reranker.score(query, [p.text for p in request.passages])
            _timing(response, admitted)
        return RerankResponse(
            model=info.name,
            revision=info.revision,
            query_truncated=truncated,
            scores=[PassageScore(id=passage_id, score=score) for passage_id, score in zip(ids, scores, strict=True)],
        )

    return app


def _timing(response: Response, admitted: Admitted) -> None:
    response.headers["X-Queue-Ms"] = f"{admitted.queue_ms:.0f}"
    response.headers["X-Compute-Ms"] = f"{admitted.compute_ms():.0f}"


def _fit_query(tokens: TokenCounter, query: str, passages: list[str]) -> tuple[str, bool]:
    """Cuts the query so that it fits with the longest passage in the reranker's limit. Passages are never cut: the control plane's chunk budget
    leaves room for a query, and a passage that does not leave room for any of it is refused."""
    limit = tokens.max_tokens
    longest = max(range(len(passages)), key=lambda i: tokens.count(passages[i]))
    passage = passages[longest]
    if tokens.count(query, passage) <= limit:
        return query, False
    # A one-token query with this passage: what is left of the limit is the query's room.
    room = limit - tokens.count(passage) - 1
    if room < 1:
        raise ModelServiceError(422, "TEXT_TOO_LONG", f"passage {longest} leaves no room for a query within {limit} tokens")
    cut = query[: tokens.fitting_chars(query, room + 2)]
    while cut and tokens.count(cut, passage) > limit:
        cut = cut[: tokens.fitting_chars(cut, tokens.count(cut) - 1)]
    if not cut.strip():
        raise ModelServiceError(422, "TEXT_TOO_LONG", f"passage {longest} leaves no room for a query within {limit} tokens")
    return cut, True


def app() -> FastAPI:
    """Factory for uvicorn: `uvicorn model_service.app:app --factory`."""
    settings = Settings.from_env()
    return create_app(
        FastEmbedEmbedder(settings.embedding_model, settings.embedding_revision, settings.model_cache_dir),
        FastEmbedReranker(settings.reranker_model, settings.reranker_revision, settings.model_cache_dir),
        settings,
    )
