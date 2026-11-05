-- Cleanup, export and the storage gauges read the audit tables by time across tenants (ADR-0013). The existing indexes lead with the tenant,
-- so without these the oldest rows are found only by scanning the whole table.
create index audit_event_occurred_idx on audit_event (occurred_at);
create index query_execution_created_idx on query_execution (created_at);
