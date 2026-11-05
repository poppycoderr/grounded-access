-- The tokenizers that decide where chunks are cut are part of the index profile (ADR-0012). Existing profiles did not record them: 'unrecorded'
-- never equals an identity the model service reports, so a database written before this change is refused at startup and has to be reloaded.
alter table index_profile add column chunk_tokenizer text not null default 'unrecorded';

alter table index_profile alter column chunk_tokenizer drop default;

alter table index_profile drop constraint index_profile_identity;
alter table index_profile add constraint index_profile_identity unique (embedding_model, embedding_revision, dimensions, query_prefix, passage_prefix,
    embedding_context, chunker_release, chunk_max_words, chunk_overlap_words, chunk_max_tokens, chunk_max_chars, heading_max_chars, chunk_tokenizer);
