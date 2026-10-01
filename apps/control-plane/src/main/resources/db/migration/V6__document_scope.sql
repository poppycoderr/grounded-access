-- Scope of a document version: where it applies and when it is valid. Scope is not authorization (ADR-0003): it narrows what is relevant to a
-- query and never decides who may read a row. An empty region array applies everywhere; a null bound leaves the window open.
alter table document_version
    add column applies_to_regions text[] not null default '{}',
    add column valid_from timestamptz,
    add column valid_to timestamptz,
    add column scope_sha256 text,
    add constraint document_version_validity_check check (valid_from is null or valid_to is null or valid_from < valid_to);

-- The fingerprint DocumentScope.sha256() gives a scope without regions or bounds.
update document_version set scope_sha256 = encode(sha256(convert_to(E'\n\n', 'UTF8')), 'hex');
alter table document_version alter column scope_sha256 set not null;

alter table ingestion_job_document
    add column applies_to_regions text[] not null default '{}',
    add column valid_from timestamptz,
    add column valid_to timestamptz;
