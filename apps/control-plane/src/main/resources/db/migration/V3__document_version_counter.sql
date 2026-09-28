-- The highest version number ever written for a document. max(version_no) cannot serve: cleanup deletes old versions, and a key that is deleted,
-- cleaned up and ingested again must not reuse a version number that once named other content, because evaluation labels and citations refer
-- to key and version.
alter table document add column last_version_no int not null default 0;

update document d set last_version_no = coalesce((select max(v.version_no) from document_version v where v.document_id = d.id), 0);
