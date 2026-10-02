-- What happened to which resource, by whom, under which policy. Written in the same transaction as the action it records, or before a query
-- result is returned, so an action without an audit row does not happen. Attributes hold identifiers and counts only: never query text, chunk
-- text, document titles or embeddings.
create table audit_event (
    id             uuid primary key,
    occurred_at    timestamptz not null default now(),
    tenant_id      text        not null,
    principal_id   text        not null,
    action         text        not null,
    resource_type  text        not null,
    resource_id    text        not null,
    decision       text        not null check (decision in ('allow', 'deny')),
    policy_version text,
    trace_id       text        not null,
    attributes     jsonb       not null default '{}'
);

create index audit_event_tenant_time_idx on audit_event (tenant_id, occurred_at);
create index audit_event_trace_idx on audit_event (trace_id);

-- How one retrieval request was executed: the plan, the policy and model versions, what was degraded and how long each stage took. It holds
-- no query text; the trace id links it to its audit event and to logs.
create table query_execution (
    id               uuid primary key,
    tenant_id        text        not null,
    principal_id     text        not null,
    trace_id         text        not null,
    plan_hash        text        not null,
    plan             jsonb       not null,
    policy_version   text        not null,
    embedding_model  text,
    status           text        not null check (status in ('ok', 'degraded')),
    degraded_reasons text[]      not null default '{}',
    result_count     int         not null,
    latency_ms       jsonb       not null,
    created_at       timestamptz not null default now()
);

create index query_execution_owner_idx on query_execution (tenant_id, principal_id, created_at);
