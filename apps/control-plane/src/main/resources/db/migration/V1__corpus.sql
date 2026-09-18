create extension if not exists vector;

create table tenant (
    id         text primary key,
    name       text        not null,
    created_at timestamptz not null default now()
);

-- A logical document; readers only ever see the version that active_version_id points to.
create table document (
    id                uuid primary key,
    tenant_id         text        not null references tenant (id),
    external_key      text        not null,
    status            text        not null default 'active' check (status in ('active', 'disabled', 'deleted')),
    active_version_id uuid,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    unique (tenant_id, external_key)
);

create table document_version (
    id              uuid primary key,
    document_id     uuid        not null references document (id),
    tenant_id       text        not null references tenant (id),
    version_no      int         not null,
    content_sha256  text        not null,
    title           text        not null,
    source_uri      text,
    chunker_version text        not null,
    embedding_model text        not null,
    created_at      timestamptz not null default now(),
    unique (document_id, version_no)
);

alter table document
    add constraint document_active_version_fk foreign key (active_version_id) references document_version (id) deferrable initially deferred;

-- tenant_id is denormalized so the tenant predicate applies before any join and can later drive partitioning.
create table chunk (
    id           uuid primary key,
    tenant_id    text    not null references tenant (id),
    document_id  uuid    not null references document (id),
    version_id   uuid    not null references document_version (id) on delete cascade,
    ordinal      int     not null,
    section_path text    not null default '',
    char_start   int     not null,
    char_end     int     not null,
    content      text    not null,
    content_tsv  tsvector generated always as (to_tsvector('english', content)) stored,
    embedding    vector(384),
    token_count  int     not null,
    unique (version_id, ordinal)
);

create index chunk_tenant_idx on chunk (tenant_id);
create index chunk_version_idx on chunk (version_id);
create index chunk_tsv_idx on chunk using gin (content_tsv);
