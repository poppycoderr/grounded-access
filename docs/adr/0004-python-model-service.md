# ADR-0004: One Python model service for embeddings and reranking

- Status: Proposed
- Date: 2026-09-18

## Context

Dense retrieval needs embeddings at ingestion time and at query time. The project promises a quickstart with no API key, so a local embedding runtime is mandatory. Reranking also needs a local cross-encoder. The runtime is either Python (sentence-transformers) or Java (ONNX Runtime / DJL).

## Decision

- Run **one Python FastAPI service, `model-service`**, which exposes:
  - `POST /v1/embed` — `{model, texts[]}` → `{model, revision, dimensions, vectors[]}`
  - `POST /v1/rerank` — `{model, query, passages[{id, text}]}` → `{model, revision, scores[{id, score}]}`
  - `GET /v1/models` — loaded models, revisions, dimensions, max input length
- It runs on CPU, with model weights baked into the image at build time. It never downloads anything at runtime.
- Candidate models, to be confirmed at M0 after checking licenses and measuring CPU latency: a small English embedding model (about 384 dimensions) and a base-size cross-encoder. The eval `run.json` records each model's name and revision.
- Contract: OpenAPI in `packages/contracts/model-service.yaml`, with contract tests on both sides. Each request carries `traceparent` and `x-request-id`. The service has no identities, no tenant IDs and no database access, and only receives text the control plane has already authorized.
- Only **one active embedding model** per deployment in v0.1. Changing it means a full re-index. `document_version.embedding_model` records which model produced each vector.
- The demo corpus ships **precomputed embeddings** keyed by `(content hash, chunker_version, model@revision)`. The service still embeds queries.

## Consequences

- The model service sits on the online path. Its failure modes are specified in the architecture overview: dense degrades to sparse-only, and rerank degrades to the fused order.
- Two runtimes to build, test and ship, which is accepted as part of showing a Java/Python boundary with a real contract.
- CPU inference keeps the quickstart portable but slow at scale. Latency is reported, not hidden.

## Alternatives considered

- **Java ONNX Runtime / DJL in-process:** one fewer service and simpler operations. Rejected for v0.1 because the Python ecosystem makes model choice and evaluation tooling simpler. Keep it as a fallback if the Python service turns out to be a burden without adding value.
- **Ollama for embeddings:** easy to install, but adds a large dependency to the quickstart and has no cross-encoder reranking. It stays the optional path for **generation** only.
- **Offline reranker CLI only:** fine as a stopgap during M1, but it cannot serve `/query`.
