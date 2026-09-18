# ADR-0001: Modular monolith first

- Status: Proposed
- Date: 2026-09-18

## Context

The control plane covers identity, authorization, ingestion, retrieval, answering and audit. Splitting these into services or build modules early would add network hops, contracts and deployment steps. That would slow down a single maintainer and wouldn't make the project any stronger as evidence. Module boundaries still matter, though. Above all, nothing may be able to read chunks without going through authorization.

## Decision

- Build the control plane as **one deployable Spring Boot application in one build module**, organised into packages (see the architecture overview, §3).
- Enforce package dependencies with architecture tests (ArchUnit or Spring Modulith verification) that run in CI.
- Run the ingestion worker in-process as a scheduled poller over the `ingestion_job` table using `FOR UPDATE SKIP LOCKED`. There is no message broker.
- The Python model service is the only separate process, because of the runtime it needs (ADR-0004).

## Consequences

- One JVM to run and debug, and one transaction manager.
- Boundaries are real because a failing test enforces them.
- Revisit this decision when a package needs to scale or ship on its own, or when multiple build modules would catch dependency mistakes that tests currently miss.

## Alternatives considered

- **Multi-module build from day one** (`ga-domain`, `ga-application`, …): stricter at compile time, but early on these modules would mostly contain forwarding code.
- **Separate ingestion service with a queue:** closer to large deployments, but no v0.1 requirement calls for it.
