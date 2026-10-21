# ADR-0006: What a document version is, and what makes a new one

- Status: Accepted
- Date: 2026-10-16

## Context

Evidence is cited as `key@version`, audit rows and execution records name versions, and the evaluation labels a version per document. So the version number has to mean something stable. Until now it was defined by what the code happened to compare:

- content and format were compared by hash;
- labels and scope were compared by SHA-256 fingerprints of a delimited encoding, which was ambiguous: `{"engineering", "finance"}` and `{"engineering<U+001F>finance"}` had the same fingerprint, so a change that narrowed access could be taken as "unchanged";
- title and source link were not compared at all, so a submission that only changed them was dropped.

The 2026-10-15 review (R1, R5) asked for one definition, applied the same way before and after the document lock.

## Decision

- **A version is an immutable snapshot of a document's content and metadata.** Metadata is the title, the source link, the access labels (classification, departments, projects) and the scope (regions, validity window).
- **Any change to content or metadata creates the next version number.** Nothing else does.
  - Changed content or format takes the full path: chunk, embed, write a new version with its own chunks.
  - Changed metadata only creates a new version that takes over the chunks of the version it replaces, in the same transaction, without calling the model service. A label change therefore applies to the next query even while the model service is down.
- **Metadata is compared by value, field by field.** Sets are compared as sets, so order does not matter, and no encoding of the values is involved. The same comparison runs in the pre-check and again under the document lock.
- **Normalization before comparison:** an empty source link is no source link; validity bounds are kept to the microsecond, the precision PostgreSQL stores. Label and region values must not be blank or contain control characters; the API and the ingestion code reject them.
- **What does not create a version:**
  - a resubmission with the same content and metadata ("unchanged");
  - a status change (`disabled`, `deleted`), which is a property of the document, not of a version;
  - a new chunker release or a different embedding configuration. Those change the index, not the document. They take effect only on a fresh index, and the evaluation refuses a corpus that mixes chunker releases. How an index is identified and rebuilt is a separate decision (open question Q13).

## Consequences

- Fingerprint columns are no longer needed and are dropped (migration V10). Existing versions keep their numbers; nothing is recomputed.
- A title or link correction now produces a new version number, so `key@version` citations made before it point at a version that is no longer readable. The chunks move to the new version in the same transaction, so this happens at once, not when cleanup removes the old row a minute later. That was already true for label changes. Citations are a snapshot, not a resolvable reference ([ADR-0011](./0011-what-a-request-sees.md)).
- Rolling V10 back means adding the columns again and filling them from the stored fields; no other data changes.
- Tests: `VersionMetadataTest` (including a property test over sets with separators, quotes and control characters), `DocumentMetadataIT` (narrowing labels hides the document from search, listing and document reads with the model service down; title and link changes keep the embeddings; resubmission and reordering are unchanged).
