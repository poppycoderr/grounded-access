FROM python:3.12-slim-bookworm
COPY --from=ghcr.io/astral-sh/uv:0.12 /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    GA_MODEL_CACHE_DIR=/models

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev

# Weights are baked in at build time; at runtime the service never downloads anything
ENV PATH=/app/.venv/bin:$PATH
RUN python -m model_service.download
ENV HF_HUB_OFFLINE=1

RUN useradd --system --uid 10001 app && chown -R app /models
USER app
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/healthz')"]
CMD ["uvicorn", "model_service.app:app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
