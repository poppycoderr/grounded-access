-- Access labels of a document version (docs/architecture/authorization.md). Existing versions become public and unrestricted, which is what
-- they were while only the tenant was enforced. classification_rank is generated so the predicate compares integers and the order is defined
-- in one place.
alter table document_version
    add column classification text not null default 'public' check (classification in ('public', 'internal', 'confidential', 'restricted')),
    add column classification_rank smallint not null generated always as (
        case classification when 'public' then 0 when 'internal' then 1 when 'confidential' then 2 else 3 end) stored,
    add column allowed_departments text[] not null default '{}',
    add column required_projects text[] not null default '{}',
    add column labels_sha256 text;

-- The fingerprint AccessLabels.sha256() gives public, unrestricted labels.
update document_version set labels_sha256 = encode(sha256(convert_to(E'public\n\n', 'UTF8')), 'hex');
alter table document_version alter column labels_sha256 set not null;

alter table ingestion_job_document
    add column classification text not null default 'public' check (classification in ('public', 'internal', 'confidential', 'restricted')),
    add column allowed_departments text[] not null default '{}',
    add column required_projects text[] not null default '{}';
