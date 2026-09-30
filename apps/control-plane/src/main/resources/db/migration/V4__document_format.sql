-- The format a version was chunked as. Together with content_sha256 it decides whether a submission is unchanged: the same text submitted as
-- plain text instead of Markdown is chunked differently, so it is a new version.
alter table document_version add column format text not null default 'markdown' check (format in ('markdown', 'text'));
alter table document_version alter column format drop default;

alter table ingestion_job_document add column format text not null default 'markdown' check (format in ('markdown', 'text'));
alter table ingestion_job_document alter column format drop default;
