# ADR-0005: `asOf` filters the current version by its validity window

- Status: Accepted
- Date: 2026-10-02

## Context

Documents carry a validity window (`valid_from`, `valid_to`), and a query can say which moment it is asking about with `asOf`. Open question Q11 asked what that moment selects. There are two readings:

1. **Filter.** `asOf` is compared with the validity window of each document's *current* version. A document whose window does not contain the moment is out of scope for the query.
2. **Historical selection.** `asOf` reconstructs the corpus as it was at that moment: for each document, the version that was active then.

The second reading needs rules the project does not have yet:

- which version wins when several were written on one day;
- whether the principal's access is judged by the labels of the historical version or of the current one, and what happens to a version the principal could not see at the time;
- how long replaced versions are kept, given that cleanup removes them within a minute today;
- an evaluation oracle that knows the expected version for each moment.

Getting any of these wrong would return content under the wrong labels, which is an authorization failure, not a relevance problem.

## Decision

- `asOf` uses the first reading. It defaults to the time of the request.
- A version is in scope when `valid_from <= asOf < valid_to`. A missing bound is open on that side.
- Only the current version of a document is ever a candidate. A replaced version is never returned, whatever `asOf` says.
- Authorization always uses the labels of the current version. `asOf` cannot change who may read a document.
- "What was the policy in 2025?" is answered by keeping the 2025 policy as its own document with a closed validity window, next to the current one. It is not answered by looking inside the history of one document.
- Validity and region are scope, not authorization (ADR-0003). A result outside the scope is reported as a scope failure and is never counted as a security violation.

## Consequences

- The retrieval join does not change: it still reads the active version of an active document, with one more condition on the validity columns.
- Cleanup may keep deleting replaced versions.
- A corpus that wants to answer questions about the past has to publish superseded policies as separate, dated documents. This is how many policy collections are organised anyway, and it makes the expected answer for a given moment explicit.
- Historical selection stays possible later. It would be a new ADR that settles the four points above, adds a retention period for replaced versions, and extends the evaluation oracle.

## Alternatives considered

- **Historical selection now:** rejected for v0.1 because of the open rules listed above, and because a mistake would be a leak.
- **No time parameter at all:** rejected. Validity windows without a way to ask about another moment would make expired documents unreachable even for legitimate questions about the past.
