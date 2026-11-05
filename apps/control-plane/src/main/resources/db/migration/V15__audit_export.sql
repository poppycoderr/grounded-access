-- What has been copied off the database (ADR-0013). Each row is one export: every audit event and execution record created before `through`,
-- written as files whose manifest has this SHA-256. A cleanup may remove only rows older than the latest `through`.
create table audit_export (
    id               uuid primary key,
    through          timestamptz not null,
    audit_events     bigint      not null,
    query_executions bigint      not null,
    manifest_sha256  text        not null,
    exported_at      timestamptz not null default now()
);

create index audit_export_through_idx on audit_export (through);
