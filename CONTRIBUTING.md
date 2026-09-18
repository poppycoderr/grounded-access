# Contributing

Thanks for your interest in Grounded Access. The project is in its first milestones, so the most useful contributions right now are issues that challenge the design, new evaluation cases, and small pull requests against open milestone issues.

## Before you start

- Read the [architecture overview](docs/architecture/overview.md), the [authorization model](docs/architecture/authorization.md) and [AGENTS.md](AGENTS.md). The non-negotiable rules in AGENTS.md apply to everyone, human or AI.
- For anything larger than a bug fix, open an issue first. Changes to authorization, telemetry redaction or the evaluation method need an ADR.

## Development

Requirements: JDK 21+, Maven 3.9+, Docker, Python 3.12 with [uv](https://docs.astral.sh/uv/).

```bash
docker compose up -d        # PostgreSQL with pgvector
mvn -B -ntp verify          # control plane: tests, integration tests, format check
mvn spotless:apply          # fix formatting findings
```

## Pull requests

- Branch from `main` as `<type>/<short-description>` (`feat/`, `fix/`, `docs/`, `build/`, `refactor/`, `test/`).
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/) in English: one line, one logical change.
- Keep a PR focused on one change. Merges into `main` are rebase-only.
- A PR that changes retrieval, chunking or filtering must include its effect on the evaluation.

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
