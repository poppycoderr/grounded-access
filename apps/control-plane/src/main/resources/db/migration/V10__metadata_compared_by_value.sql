-- Whether a submission changes a document's labels or scope used to be decided by comparing SHA-256 fingerprints of a delimited encoding.
-- The encoding was ambiguous: {"a", "b"} and {"a<U+001F>b"} had the same fingerprint. Ingestion now compares the stored columns
-- themselves, so the fingerprints are no longer read and are dropped. No version changes: the columns they summarized stay as they are.
alter table document_version drop column labels_sha256;
alter table document_version drop column scope_sha256;
