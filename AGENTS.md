# AGENTS.md

This file guides AI coding agents (Claude Code, Codex and others) working on Grounded Access. Human contributors follow the same rules.

Read these before changing behaviour:

- [Architecture overview](docs/architecture/overview.md)
- [Authorization model](docs/architecture/authorization.md)
- [Evaluation strategy](docs/evaluation/strategy.md)
- [ADRs](docs/adr/) and [milestones](docs/project/milestones.md)

## Build and verify

```bash
mvn -B -ntp verify                                  # control plane: format check, tests, architecture tests
mvn spotless:apply                                  # fix Java formatting findings
uv run --directory apps/model-service pytest        # model service
uv run --directory packages/evaluation pytest       # evaluation CLI
docker compose up -d                                # PostgreSQL + model service for local runs
```

NullAway only runs on classes the compiler actually recompiles, so run `mvn -B -ntp clean verify` before pushing; an incremental build can miss a nullness error that CI and the Docker image build will catch.

Integration tests use Testcontainers and need a running Docker daemon. Do not skip, disable or weaken a failing check just to get a green build.

## Layout

| Path | Role |
|---|---|
| `apps/control-plane` | Java / Spring Boot: identity, authorization, ingestion, retrieval, answering, API |
| `apps/model-service` | Python / FastAPI: embeddings and reranking; no identities, no database access |
| `packages/contracts` | OpenAPI contracts between the control plane and the model service |
| `packages/evaluation` | Python `ga-eval` CLI: dataset validation, metrics, reports |
| `data/` | Fictional corpus, manifests, principals, evaluation cases |
| `docs/` | Public architecture, ADRs, evaluation and project docs, all in English |

## Non-negotiable rules

These rules come from the design. Changing one of them requires an ADR first.

1. **Authorization happens in SQL.** Every query against `chunk` goes through the compiled authorization predicate (`AuthorizedChunkQuery`). Unauthorized rows are never filtered in Java after they have been fetched.
2. **Principal attributes come only from the verified token**, never from request bodies or query parameters.
3. **Nothing reveals whether hidden content exists.** Invisible documents return 404; queries answer `no_answer`; nothing reports how many rows were filtered out.
4. **Authorization filters and scope filters are separate things.** Tenant, clearance, department and project are authorization. Region, validity and status are scope. Do not merge them.
5. **Sensitive text stays out of telemetry.** Query text, chunk text, prompts, model output, embeddings and document titles never go into logs, spans or metrics.
6. **The model service holds no identities and never touches the database.** It only receives text the control plane has already authorized.
7. **Retrieval changes come with evaluation evidence.** A PR that changes ranking, chunking or filtering includes the eval impact. Benchmark numbers must come from committed or released result files.
8. **Stay within scope.** No knowledge graphs, GraphRAG, agents, extra vector stores, Kubernetes or cloud providers before v0.1. See the README.

## Code conventions

- **Java:** 4-space indent, lines up to about 150 columns, one record component per line. Rich domain objects with no public setters. `*Request` / `*Response` DTOs in their own files with `static from(...)`. Explicit `@PathVariable("id")` and `@RequestParam(value = "...")`.
- **Python:** 3.12, typed, Pydantic models at every boundary, `ruff` for linting and formatting, `pytest` for tests.
- **Comments:** class-level comments that say what a type is for. Method comments only for pitfalls (concurrency, SQL predicates, ranking math). No line-by-line comments and no TODO placeholders.
- **Tests:** cover core logic and failure paths rather than chasing a coverage number. Authorization changes need unit tests, property-based tests and SQL-level integration tests.
- **Dependencies:** check the library docs rather than relying on memory. Don't add a framework or toolchain without a reason recorded in the PR.

## Git and pull requests

- Never commit directly to `main`. Branch as `<type>/<short-description>`, for example `feat/policy-compiler`, `fix/fts-query-builder`, `docs/threat-model` or `build/ci-matrix`. Don't use tool-specific prefixes such as `codex/` or `claude/`.
- Branch from the latest `main` and open the PR against `main`. Merges are rebase-only. A branch contains only its own change; to resolve a conflict, rebase onto `main`.
- **Commits:** Conventional Commits in English, `<type>(<optional scope>): <subject>` with a lowercase imperative subject and a one-line message with no body. One logical change per commit. No emoji, no AI attribution and no `Co-Authored-By` trailers.
- **PR titles:** English, same format as commits.
- **PR descriptions:** bilingual. Write the full English version first, then a horizontal rule (`---`), then the Chinese version with the same content. Describe the feature itself: background, scope, data model or contract changes, key design decisions. Leave out sections such as "testing", "risks" or "deployment" (CI shows test results) and AI attribution footers.
- **PR diagrams:** follow the `show-me` skill. Use the smallest view that makes the point: a shallow file tree for layout, a call tree or `mermaid` sequence for control flow, a `diff` for what changes, pseudocode for an algorithm. Use 1–3 diagrams per PR, written as `text`, `diff` or `mermaid` code blocks and placed next to the text they support. Put them in the English section; the Chinese section refers to them rather than repeating them.
- A first PR for a feature describes the feature as new. It doesn't list bugs that were fixed during development.
- Only commit, push or open a PR when asked to. Never merge to `main`, force-push shared branches or trigger releases without explicit instruction.

## Documentation

- Public docs under `docs/` and the READMEs are in English, precise and free of marketing language.
- Agent scratch work (reviews, plans, visualizations) goes in `.agentdocs/`, which git ignores. Never force-add it.
- When behaviour, configuration or an API changes, update the matching doc in the same PR.

## Working language

Talk to the maintainer in Chinese unless English is needed for accuracy (error messages, identifiers, quotes). Code, commits, PR titles, logs and public docs are in English.
