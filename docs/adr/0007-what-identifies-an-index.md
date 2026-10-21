# ADR-0007: What identifies an index

- Status: Accepted
- Date: 2026-10-20

## Context

A dense search compares a query vector with stored chunk vectors. The comparison means something only if both were produced the same way: by the same model weights, with the same query instruction, from chunks cut by the same chunker and embedded with the same text around them. Today that is checked by name only:

- at startup, `EmbeddingModelGuard` compares the configured model name with the part before `@` of each active version's `embedding_model`; the revision, the query prefix, the embedding context and the chunker release are not compared;
- the embedding client takes whatever the model service returns: it does not check the model name, the revision, the dimensions, the number of vectors or their values, and keeps the revision of the last batch;
- the dense query has no condition on how the stored vectors were made;
- model weights are downloaded by name when the image is built, so a rebuild can pick up a newer revision under the same name, and the service reports `unknown` when it cannot tell which revision it loaded.

A rebuilt model service, a changed `GA_EMBEDDING_CONTEXT` or a new chunker release can therefore leave query vectors and stored vectors in different spaces. Search still returns results, and they are wrong without any error. The 2026-10-15 review (R4) asked for an identity of the index that is separate from a document's version and from a query's plan, checked at startup and on every model response. Open question Q13 recommended the same.

## Decision

### Three things, three identities

| | What it identifies | Where it lives | What changes it |
|---|---|---|---|
| **Document version** | what a document says and who may read it | `document_version.version_no` ([ADR-0006](./0006-what-a-document-version-is.md)) | content or metadata |
| **Index profile** | how the stored chunks and vectors were made | new table `index_profile`, referenced by every version | model, revision, query prefix, embedding context, chunker release and chunk size |
| **Retrieval plan** | how one query is run against the index | `query_execution.plan` and `plan_hash` | the request and the server defaults |

They do not stand in for each other. A new profile does not create a document version, a new version does not change the profile, and the plan hash does not include the profile. The execution record names both the plan and the profile.

### What is in a profile

- embedding model name;
- exact model revision (the Hugging Face commit of the weights);
- vector dimensions;
- query prefix, and passage prefix (empty for every model so far);
- whether chunks are embedded with their heading path (`GA_EMBEDDING_CONTEXT`);
- chunker release, which includes text normalization, and the chunk size settings (maximum words per chunk and overlap words).

The full-text index is not in the profile. It is a generated column, so a migration rebuilds it for every row at once (V9 did), and stored rows cannot disagree with it.

A profile is stored once and referenced by `document_version.index_profile_id`. Profiles are compared field by field, like metadata in ADR-0006; there is no fingerprint. The existing `embedding_model` and `chunker_version` columns stay, because the API and the evaluation read them, and are written together with the profile.

### The expected profile

The control plane builds the profile it expects from two sources: the model service (name, revision, dimensions, query and passage prefix) and its own configuration (embedding context, chunker release, chunk size). The model name it is configured with must equal the name the service reports.

**The index is compatible** when every active version references a profile equal to the expected one. An empty corpus is compatible with any profile; its first ingestion records the profile.

A version created by a label or link change (ADR-0006) keeps the profile of the version it replaces, because it keeps that version's chunks and vectors.

### Model artifacts are pinned

- The repository pins the exact revision of the embedding model and the reranker next to their names. The image build downloads those revisions and fails if it cannot.
- The model service refuses to start if the weights it loads are not the pinned revision. It no longer reports `unknown`.

### Every model response is checked

An embedding response is accepted only if:

- the model name is the expected one, and the revision is the expected one and not `unknown`;
- the dimensions are the profile's and the vector column's;
- the prefix it reports having applied is the profile's query or passage prefix (the response contract gains this field);
- there is exactly one vector per input text, each with that many values;
- every value is finite and no vector is all zeros, because a cosine distance with such a vector is not a number.

Every batch of one call is checked the same way, so one call cannot mix revisions. A reranking response is accepted only if it has one finite score per passage, with the passage ids that were sent. The reranker's revision is recorded in the execution record. It is not part of the index profile, because it reads text, not stored vectors.

### What happens when a check fails

| Situation | Behaviour |
|---|---|
| Startup, model service reachable, active versions have a different profile (or more than one) | Refuse to start, naming both profiles. The fix is to configure the previous model and settings again, or to load the corpus into an empty database. This is what the name check does today, applied to every field. |
| Startup, model service unreachable | Start. Sparse search, document reads, listings and label changes do not need the model service. Dense search tries the service per query and falls back as today (`dense_unavailable`). |
| Query, response fails the checks above | Dense results are not used. A hybrid search returns its sparse results, still authorized, with the reason `dense_invalid_response`; a dense-only search fails. |
| Query, valid response from a different profile (the service was replaced while running) | The same, with the reason `dense_index_mismatch`. Vectors from two profiles are never compared. |
| Ingestion, either of the two above | The attempt fails before its write transaction, with a code that names the mismatch, and is not retried. Nothing is written; the active version is unchanged. |

The reranker follows the same rule: a response that fails its checks is treated like an unavailable reranker (`rerank_unavailable`), and the fused order is returned.

### Existing data

A migration creates profiles from what each existing version recorded:

- name and revision from `embedding_model` (`name@revision`);
- dimensions from the vector column (384);
- embedding context from the chunker release: release 3 is the context release, and the code that wrote these rows set it only with context on;
- chunk size from the defaults, 180 and 30 words, which have not changed since overlap was introduced (`b34cfee`) and were never exposed as environment variables;
- query prefix from the model name, using the table the model service has used since it was written (`b8ba65a`), which has not changed. This is what the code that wrote the vectors did, not a guess.

A version whose revision was recorded as `unknown` gets a profile with that revision. It never equals an expected profile, so the control plane refuses to start against it and asks for a rebuild. No data is deleted.

### One profile per database

Every check above compares a process with the data. Two processes with different configurations, both started while the database was empty, would each establish their own profile and write it. Since review 2026-10-21 (D6) the database is anchored to the profile of its first written version, and other profiles are refused at write time and at startup ([ADR-0011](./0011-what-a-request-sees.md)).

### Not decided here

Rebuilding an index next to the live one and switching to it atomically, with a rollback, needs an index generation that the active pointer can follow. That is the next step and gets its own ADR. Until then, changing the profile means loading the corpus into an empty database, as it does today.

## Consequences

- A rebuilt model service, a changed context setting or a new chunker release is detected at startup and on every response, instead of producing wrong neighbours.
- A deployment upgraded from v0.1.0 keeps its data if its stored revision is known and matches the pinned one. Otherwise it refuses to start until the corpus is reloaded or the previous model is configured again.
- Two new degraded reasons, `dense_invalid_response` and `dense_index_mismatch`, appear in responses, execution records and traces.
- Changing the pinned revision is a profile change. The pull request that does it must also say how the index is rebuilt.
- The evaluation can record the profile of the index it measured; the run manifest (R8) uses it.
- Rolling the migration back drops the table and the column. No other data changes.
- Tests to add: the same name with a different revision, the same revision with a different context, chunker release or chunk size, mixed revisions across batches, wrong dimensions, a wrong vector count, non-finite values, a zero vector, a service replaced while running, startup with the service down, and existing rows with a known and an `unknown` revision.
