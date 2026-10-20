-- Chunker release 4 bounds every chunk (ADR-0009): a character cap, a token budget for the embedding input and a limit on the embedded heading
-- path. They are part of the index profile. Profiles of earlier releases had none of them; 0 records that.
alter table index_profile
    add column chunk_max_tokens  int not null default 0,
    add column chunk_max_chars   int not null default 0,
    add column heading_max_chars int not null default 0;

alter table index_profile
    alter column chunk_max_tokens drop default,
    alter column chunk_max_chars drop default,
    alter column heading_max_chars drop default;

alter table index_profile drop constraint index_profile_embedding_model_embedding_revision_dimensions_key;
alter table index_profile add constraint index_profile_identity unique (embedding_model, embedding_revision, dimensions, query_prefix, passage_prefix,
    embedding_context, chunker_release, chunk_max_words, chunk_overlap_words, chunk_max_tokens, chunk_max_chars, heading_max_chars);

-- Whether the reranker had to cut the query of an execution so that it fit with the passages; null when nothing was reranked.
alter table query_execution add column rerank_query_truncated boolean;
