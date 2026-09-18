# ADR-0002: PostgreSQL full-text search and pgvector exact search, fused with RRF

- Status: Proposed
- Date: 2026-09-18

## Context

The sparse and dense channels have to apply the **same** authorization predicate inside the database (ADR-0003). The demo corpus is small, about 500–1,500 chunks. The benchmark has to be honest about how strong each baseline is.

## Decision

1. **Sparse channel: PostgreSQL built-in full-text search.**
   - A generated `tsvector` column using the `english` configuration, with a GIN index.
   - Queries are built with OR semantics. `plainto_tsquery` / `websearch_to_tsquery` AND every term together, so natural-language questions would rarely match. The query builder instead takes the normalized lexemes and joins them with `|`.
   - Ranking uses `ts_rank_cd`. **This is not BM25**: it uses no corpus-level IDF. Documentation and reports call it "PostgreSQL FTS", never "BM25".
2. **Dense channel: pgvector with exact search.**
   - `ORDER BY embedding <=> :q LIMIT :k` over rows that pass the predicate, with **no ANN index** in v0.1. At demo scale this is fast enough, and it keeps the recall invariant from the authorization model trivially true.
3. **Fusion: Reciprocal Rank Fusion.** `score(d) = Σ_c w_c / (k + rank_c(d))`, with `k = 60` and `w_c = 1` by default. Per-channel candidate counts, `k` and the weights are part of the `RetrievalPlan` and are tuned only on the `dev` split.
4. **Calibration row:** the evaluation reports a Python BM25 reference over the same authorized chunks (see evaluation strategy). This makes clear how much of any hybrid gain comes from a weak sparse channel.

## Consequences

- There is one datastore and one filtering surface, and the full predicate is visible in `EXPLAIN ANALYZE`.
- Sparse quality is expected to trail BM25, and the report states this openly.
- Exact vector search stops being viable somewhere well beyond demo scale. That point is measured, not guessed.

## Planned follow-up (not v0.1)

A benchmark case with a **highly selective filter** (for example a project that owns about 2% of chunks) comparing exact search with HNSW:

- default `hnsw.ef_search`;
- pgvector ≥ 0.8 iterative index scans (`hnsw.iterative_scan`);
- per-tenant partitioning or partial indexes.

The same benchmark supplies the evidence for the article on why post-filtering hurts recall.

## Alternatives considered

- **BM25 extension (e.g. ParadeDB `pg_search`):** a stronger sparse channel, but it is AGPL-licensed and adds another extension to operate. Reconsider it if the calibration row shows a large gap.
- **Elasticsearch/OpenSearch next to pgvector:** gives real BM25, but authorization would then be enforced in two query languages, which is exactly the duplication this project argues against.
- **Dedicated vector database:** out of scope for the first phase.
