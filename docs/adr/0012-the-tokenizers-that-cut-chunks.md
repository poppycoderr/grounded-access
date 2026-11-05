# ADR-0012: The tokenizers that cut chunks are part of the index profile

- Status: Accepted
- Date: 2026-11-05

## Context

Since chunker release 4 (ADR-0009) a chunk is split where its embedding input stops fitting 384 tokens, as counted by **both** models'
tokenizers: the embedding model's and the reranker's. The stricter count wins, so a stored chunk fits both models.

The index profile (ADR-0007) identifies the embedding model by its exact revision, which fixes its tokenizer. It does not identify the
reranker, on the premise that the reranker "reads text, not stored vectors". That premise was written before ADR-0009 and no longer holds
for writing: the reranker's tokenizer decides where long chunks are cut. Review 2026-10-21 (N5) found the consequence:

- Replacing the reranker with one whose tokenizer counts differently changes how new long documents are chunked.
- The profile does not change, so nothing is refused. Old and new documents of one profile are then cut by different rules. An evaluation
  run sees one chunker release and one profile and cannot tell.

The two pinned models do not change by themselves, and no wrong chunk has been observed. The gap opens at the next reranker change.

Three ways to close it were considered:

1. **Put the reranker's model and revision into the profile.** This is simple, but a new reranker revision whose tokenizer files are
   unchanged would then force a rebuild. Nothing stored depends on the reranker's weights. The execution record and the evaluation manifest
   already name the reranker revision a query used.
2. **Cut chunks by the embedding tokenizer only.** The reranker would then fit passages at query time. This breaks ADR-0009's guarantee that
   a stored chunk fits both models: the reranker would read a cut passage, or the query path would need a second fitting rule. It changes
   behaviour in order to avoid recording something.
3. **Identify the tokenizers by their content.** Record what decides chunk boundaries, and nothing else. **Chosen.**

## Decision

### The chunking tokenizer identity

- **Per tokenizer.** At startup the model service computes a SHA-256 over the files each model's token counter reads: `tokenizer.json`,
  then `tokenizer_config.json`. Each file is preceded by its length in bytes.
- **Combined.** The identity is `sha256:` followed by a SHA-256 over the two digests, the embedding model's first. The order is the one
  `/v1/tokenize` applies them in.
- **On the wire.** `POST /v1/tokenize` reports this identity in every response, as `tokenizer`. It is a digest of public model files and
  carries no text, tenant or principal.

### The profile records it

- **New field.** The index profile gains `chunk_tokenizer` (migration V14). Profiles are compared field by field, as before, and the
  identity is one of the fields.
- **Established with the rest.** When the control plane establishes its profile, it sends one tokenize probe with the existing fixed probe
  text, next to the two embedding probes, and takes the identity from the answer.
- **One profile per database.** The database anchor (ADR-0011) stores a profile id. A process whose tokenizers differ therefore produces
  another profile and is refused when it writes. This also covers a process that started while the database was empty.

### Checked whenever chunks are cut

- **Every response.** Every tokenize response that ingestion uses must report the established identity, and every batch of one call must
  report the same one. Otherwise the attempt fails before its write transaction with `INDEX_PROFILE_MISMATCH` and is not retried. Nothing is
  written, and the active version is unchanged. This is the same rule ADR-0007 applies to embeddings.
- **No identity.** A response without an identity is invalid (`MODEL_RESPONSE_INVALID`).

### Reads are not affected

Searching does not cut stored chunks. A model service whose reranker tokenizer changes while it is running keeps serving searches. Dense
search compares query vectors with stored vectors exactly as before, and the reranker fits the query with whatever tokenizer it has. A query
it had to cut is reported as `rerankQueryTruncated`. Only writing new chunks is refused.

### When the index has to be rebuilt

| Change | Profile | What to do |
|---|---|---|
| Reranker revision whose tokenizer files are byte-identical | unchanged | Nothing for the index. The revision is in execution records and evaluation manifests. |
| Reranker replaced, or a revision with different tokenizer files | changed | Load the corpus into a new database, as for any profile change. |
| Embedding model or revision | changed (as before) | The same. |
| The `tokenizers` library in the model-service image | **not detected** | The digest covers files, not the code that reads them. The library is pinned in `uv.lock`, and a pull request that upgrades it says whether chunk boundaries can change. |

Until index generations exist (open question Q15), "load the corpus into a new database" is the only rebuild. Emptying a database does not
release its anchor.

### Existing data

Migration V14 gives every existing profile the identity `unrecorded`. A real identity never takes this value.

- **Release 3 profiles** never used a tokenizer and are already refused at startup.
- **Release 4 profiles** were written from `main` after v0.1.0, which was never released. They were probably cut by the pinned tokenizers,
  but the database does not say so, and the migration does not claim it. A control plane that can reach its model service therefore refuses
  to start against such a database, names both profiles and asks for a reload. No data is deleted.

## Consequences

- A reranker change that would cut chunks differently is refused at the first write, instead of mixing two chunkings under one profile.
- A reranker revision with unchanged tokenizer files needs no rebuild.
- `/v1/tokenize` gains a required `tokenizer` field, and the control plane and the model service are upgraded together, as they already
  must be after v0.1.0. `GET /api/v1/system` and evaluation manifests show `chunkTokenizer` with the profile.
- A database written from `main` before this change has to be recreated. The upgrade notes say so.
- **Residual risk.** A change in the tokenizer library is caught by review, not by the profile. So is a third tokenizer, should one ever
  take part in cutting.
- **Tests.**
  - Model service: the identity changes with either tokenizer's files and with their order, and is reported in every tokenize response.
  - Client: a missing identity, and batches of one call reporting different identities, are invalid responses.
  - Integration: a model service whose tokenizer changes while running fails ingestion with `INDEX_PROFILE_MISMATCH`, writes nothing, and
    still serves searches. A process with other tokenizers is refused by the anchor. A migrated profile is `unrecorded` and is refused at
    startup.
