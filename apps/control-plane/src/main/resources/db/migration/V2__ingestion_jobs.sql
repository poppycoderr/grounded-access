-- A queued ingestion request. Workers claim rows with FOR UPDATE SKIP LOCKED and hold a lease while they run; an expired lease means the
-- worker died and the job may be claimed again. 'failed' is the dead-letter state; there is no separate queue.
create table ingestion_job (
    id               uuid primary key,
    tenant_id        text        not null references tenant (id),
    submitted_by     text        not null,
    status           text        not null default 'queued' check (status in ('queued', 'running', 'succeeded', 'failed')),
    document_count   int         not null,
    processed        int         not null default 0,
    created          int         not null default 0,
    updated          int         not null default 0,
    unchanged        int         not null default 0,
    chunks           int         not null default 0,
    attempts         int         not null default 0,
    max_attempts     int         not null,
    run_after        timestamptz not null default now(),
    lease_expires_at timestamptz,
    error_code       text,
    created_at       timestamptz not null default now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    check (processed <= document_count)
);

create index ingestion_job_claim_idx on ingestion_job (run_after) where status in ('queued', 'running');

-- The submitted documents, kept only until the job finishes: once written, their content lives in document_version and chunk.
create table ingestion_job_document (
    job_id       uuid not null references ingestion_job (id) on delete cascade,
    ordinal      int  not null,
    external_key text not null,
    title        text not null,
    source_uri   text,
    content      text not null,
    primary key (job_id, ordinal)
);
