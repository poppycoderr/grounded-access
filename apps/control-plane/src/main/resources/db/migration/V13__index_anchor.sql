-- The one index profile this database is written with (ADR-0007, review 2026-10-21 D6). The first version written claims it; a process with
-- another profile is refused when it writes, even if it started while the database was empty. Emptying the database does not release it: a
-- different profile means a new database.
create table index_anchor (
    singleton        boolean     primary key default true check (singleton),
    index_profile_id uuid        not null references index_profile (id),
    created_at       timestamptz not null default now()
);

-- An existing corpus whose active versions share one profile is anchored to it; a corpus that already mixes profiles is not anchored, and
-- the startup check refuses it as before.
insert into index_anchor (index_profile_id)
select (array_agg(distinct v.index_profile_id))[1]
from document d
join document_version v on v.id = d.active_version_id
having count(distinct v.index_profile_id) = 1;
