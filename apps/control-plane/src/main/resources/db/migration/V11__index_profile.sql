-- How the stored chunks and vectors were made (ADR-0007). Every version names its profile; dense search reads only rows of the profile the
-- query was embedded with, and the control plane refuses to start when the stored profile is not the one it would produce.
create table index_profile (
    id                  uuid primary key,
    embedding_model     text        not null,
    embedding_revision  text        not null,
    dimensions          int         not null,
    query_prefix        text        not null,
    passage_prefix      text        not null,
    embedding_context   boolean     not null,
    chunker_release     text        not null,
    chunk_max_words     int         not null,
    chunk_overlap_words int         not null,
    created_at          timestamptz not null default now(),
    unique (embedding_model, embedding_revision, dimensions, query_prefix, passage_prefix, embedding_context, chunker_release, chunk_max_words,
            chunk_overlap_words)
);

-- Profiles of existing versions, from what each version recorded and what the code that wrote it did:
-- name and revision from embedding_model ('name@revision'; a missing revision becomes 'unknown', which never matches a running service);
-- dimensions from the vector column; embedding context on exactly for chunker release 3, the context release; the query prefix from the
-- model service's prefix table, unchanged since it was written; no passage prefix; chunk size 180/30 words, the only defaults ever shipped.
insert into index_profile (id, embedding_model, embedding_revision, dimensions, query_prefix, passage_prefix, embedding_context, chunker_release,
                           chunk_max_words, chunk_overlap_words)
select gen_random_uuid(), model, revision, 384,
       case model when 'BAAI/bge-small-en-v1.5' then 'Represent this sentence for searching relevant passages: ' else '' end,
       '', release = '3', release, 180, 30
from (select distinct split_part(embedding_model, '@', 1) as model,
                      coalesce(nullif(split_part(embedding_model, '@', 2), ''), 'unknown') as revision,
                      split_part(chunker_version, '/', 2) as release
      from document_version) existing;

alter table document_version add column index_profile_id uuid references index_profile (id);

update document_version v
set index_profile_id = p.id
from index_profile p
where p.embedding_model = split_part(v.embedding_model, '@', 1)
  and p.embedding_revision = coalesce(nullif(split_part(v.embedding_model, '@', 2), ''), 'unknown')
  and p.chunker_release = split_part(v.chunker_version, '/', 2);

alter table document_version alter column index_profile_id set not null;

create index document_version_index_profile_idx on document_version (index_profile_id);

-- The profile an execution's dense channel searched; null when the strategy has no dense channel or it was unavailable.
alter table query_execution add column index_profile_id uuid;
