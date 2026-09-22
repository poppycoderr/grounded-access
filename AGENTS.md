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

- **Java:** rich domain objects with no public setters. `*Request` / `*Response` DTOs in their own files with `static from(...)`. Explicit `@PathVariable("id")` and `@RequestParam(value = "...")`.
- **Python:** 3.12, typed, Pydantic models at every boundary, `ruff` for linting and formatting, `pytest` for tests.
- **Tests:** authorization changes need unit tests, property-based tests and SQL-level integration tests.

## Git mode

- This repository uses **pull request mode** from the shared rules below. Run `mvn -B -ntp clean verify` before pushing a branch.

## Documentation

- Public docs under `docs/` and the READMEs are in English, precise and free of marketing language.
- When behaviour, configuration or an API changes, update the matching doc in the same PR.

<!-- shared-agent-rules:start v1 — keep this block identical (per language) in codesphere, codesphere-labs, domain-driven-kit and grounded-access -->
## Shared rules

These rules are shared by the maintainer's repositories: codesphere, codesphere-labs, domain-driven-kit and grounded-access. The project-specific sections of this file add to them; where the two conflict, the project-specific section wins.

### Working language

- Talk to the maintainer in Chinese unless English is needed for accuracy (error messages, identifiers, quotes).
- Commits, PR titles, code identifiers and logs are in English. Documentation follows the language of the repository.

### Working efficiently

- Read the relevant code and docs before changing anything, and plan a multi-file change once instead of asking after every step.
- Search narrowly: grep specific paths, read only the ranges you need, and don't re-read a file you have just edited.
- Edit with targeted replacements rather than rewriting whole files, and don't paste file contents back into the conversation.
- Run independent commands together. Run long builds or experiments in the background instead of polling them.
- Reuse decisions that are already settled (known errors, permission flows, tool choices) instead of explaining them again.
- When done, report briefly: what changed, how it was verified, the commit hash and the push result. Don't narrate your reasoning unless asked.
- Fix and retry problems yourself. Stop and ask only before risky or irreversible actions: deleting data, force-pushing, rewriting history, releases or anything touching production.

### Code

- Follow the existing style and layout. Don't add a framework or toolchain without a stated reason, and don't touch files unrelated to the task.
- Comments: a class-level comment says what a type is for; method comments only for pitfalls such as concurrency, ordering or non-obvious algorithms; no line-by-line comments and no TODO placeholders.
- Java: 4-space indent, lines up to about 150 columns, one record component per line.
- Check library and API documentation instead of relying on memory, and pin dependency and image versions.
- Tests cover core logic and failure paths rather than a coverage number. Never skip, disable or weaken a check to get a green build.
- Only report test, benchmark or measurement results you actually ran, and keep the evidence where the project says it belongs.

### Commits

- Conventional Commits in English: `<type>(<optional scope>): <subject>` with a lowercase imperative subject. One line, no body. One logical change per commit.
- No emoji, no AI attribution and no `Co-Authored-By` trailers.
- Before committing, review the staged diff: no scratch notes, build output, secrets or unrelated changes. Never `git add -f` an ignored path.
- Commit and push only when asked. Never force-push or rewrite pushed history without explicit consent.
- If a push fails because authentication expired, run `gh auth login`, continue, and report only the result.

### Branches and pull requests

Each repository uses one of two modes. The project-specific section states which.

- **Direct mode** (codesphere, codesphere-labs): commit to the default branch. No feature branches and no pull requests.
- **Pull request mode** (domain-driven-kit, grounded-access):
  - Never commit directly to `main`. Branch from the latest `main` as `<type>/<short-description>`, for example `feat/policy-compiler` or `docs/threat-model`. Don't use tool-specific prefixes such as `codex/` or `claude/`.
  - A branch contains only its own change. Resolve conflicts by rebasing onto `main`; merges are rebase-only. Don't merge a PR, publish a release or run a deployment without explicit instruction.
  - **PR title:** English, same format as a commit.
  - **PR description:** bilingual. Write the full English version first, then a horizontal rule (`---`), then a Chinese version with the same content. Describe the change itself: background, scope, contract or data model changes, key design decisions. Leave out sections such as testing, risks or deployment (CI shows test results), and leave out AI attribution footers. A first PR for a feature describes it as new rather than listing bugs fixed during development.
  - **PR diagrams:** 1–3 per PR, the smallest view that makes the point (a shallow file tree, a call tree or `mermaid` sequence, a `diff`, pseudocode), written as `text`, `diff` or `mermaid` code blocks next to the text they support. Put them in the English section; the Chinese section refers to them.

### CI and automation

- Checks triggered by pushes and pull requests run on their own; don't cancel, rerun or disable them.
- Dispatch a workflow manually only when asked, then return the run link. Don't poll it unless asked to wait or investigate.

### Scratch work and secrets

- Plans, reviews, notes and visualizations go in the repository's ignored scratch directory (`drafts/` in codesphere, `.agentdocs/` elsewhere). Don't put them in tracked docs, and don't change ignore rules to commit them.
- No passwords, tokens, internal hostnames, personal absolute paths or real business data in code, logs, evidence or docs. Use obvious demo values such as `example_password`.
<!-- shared-agent-rules:end -->
