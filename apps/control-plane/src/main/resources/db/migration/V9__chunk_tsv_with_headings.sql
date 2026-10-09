-- Keyword search covers a chunk's heading path as well as its text: a question often uses the words of the heading
-- ("launch gates") while the passage under it does not. Existing rows are re-indexed by the new generated column.
drop index chunk_tsv_idx;
alter table chunk drop column content_tsv;
alter table chunk add column content_tsv tsvector generated always as (to_tsvector('english', section_path || ' ' || content)) stored;
create index chunk_tsv_idx on chunk using gin (content_tsv);
