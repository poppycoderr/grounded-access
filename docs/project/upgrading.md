# Upgrading

## From v0.1.0 to the next release

`main` after v0.1.0 changes the schema, the chunker and the model-service contract. An index built with v0.1.0 cannot be kept: it was chunked
with chunker release 3, and the control plane refuses to start on data whose index profile it cannot produce (ADR-0007). The database's
documents, labels and audit rows survive the migrations, but searching needs the corpus loaded again into a new database.

### What changes

| Area | Change | Why it matters for the upgrade |
|---|---|---|
| Schema | Migrations V9–V13: keyword index with heading paths, metadata compared by value (fingerprint columns dropped), index profiles, chunk length limits, the database's index anchor | Applied automatically at startup. V10 drops columns, so a v0.1.0 control plane cannot use a migrated database |
| Index | Chunker release 4 (ADR-0009), index profile with pinned model revisions (ADR-0007) | Existing versions get a profile with release 3, which the new control plane refuses |
| Model service | Pinned weights, `/v1/tokenize`, token-limit refusals, lanes and `X-Deadline-Ms` (ADR-0010), `prefix` and `query_truncated` in responses | The control plane and the model service must be upgraded together |
| Text storage | The database stores chunks and a content hash, not the document text | A new index needs the source documents again |

New settings, all optional:
- `GA_EMBEDDING_REVISION` and `GA_RERANKER_REVISION`;
- `GA_RETRIEVAL_DEADLINE` and `GA_RETRIEVAL_SQL_TIMEOUT`;
- `GA_DB_CONNECTION_TIMEOUT_MS`;
- the lane sizes `GA_QUERY_SLOTS`, `GA_QUERY_QUEUE`, `GA_PASSAGE_*`, `GA_RERANK_*`, `GA_TOKENIZE_*`;
- `GA_BUILD_COMMIT`, a build argument.

### Steps

1. **Keep what you have.** Back up the v0.1.0 database (`pg_dump`) and keep the v0.1.0 images. Do not start the new control plane against
   this database: its migrations cannot be undone, and the new version would refuse the index anyway.
2. **Make sure the sources are available.** Every document has to be submitted again, from the files or the system it came from.
3. **Start the new version against a new, empty database.** With Docker Compose, use a separate project or volume, for example
   `docker compose -p ga-next up -d --build --wait`. Set `GA_BUILD_COMMIT` when you build if you want the evaluation manifest to name the
   commit.
4. **Load the corpus.** For the demo corpus: `./scripts/load-demo`.
5. **Check it.**
   - `GET /api/v1/system` (a token with the `debug` scope) shows the index profile with chunker release `4` and the pinned revisions.
   - `./scripts/demo` passes.
   - `./scripts/benchmark` reports no security violations or scope failures and passes the keyword ranking check.
6. **Switch** clients to the new deployment. Keep the old database and images until you no longer need to go back.

### Going back

Run the v0.1.0 images against the v0.1.0 database you kept, or restore it from the backup. There is no downgrade of a migrated database.

### The demo stack

The demo corpus is fictional and reloads in under a minute, so `docker compose down -v` followed by `docker compose up -d --build --wait`
and `./scripts/load-demo` is the shortest upgrade. It deletes the local database, including its audit rows.

## Changing the index profile later

The same rules apply to any later change of the embedding model or revision, `GA_EMBEDDING_CONTEXT`, the chunk size, or the chunker release:
load the corpus into a new database. The database stays anchored to the profile of its first written version, and emptying it does not
release it (ADR-0011). Rebuilding next to the live index is open question Q15.
