# Project Atlas: Data Validation

This fictional document is for members of Project Atlas only. It describes how the team proves that the migrated billing database holds the same data as the old one.

## What is compared

Every table of the billing database is compared between the old and the new cluster. Tables are compared by row count, by a checksum per partition, and, for the invoice and payment tables, row by row on a sample.

## Checksums per partition

The checksum of a partition is computed on both clusters with the same query and compared by the validation job:

```sql
SELECT partition_key,
       count(*)                                  AS row_count,
       md5(string_agg(id::text || ':' || updated_at::text, ',' ORDER BY id)) AS digest
FROM invoice
WHERE partition_key = $1
GROUP BY partition_key;
```

A partition whose digest differs is compared row by row. Rows that were updated during the comparison are compared again after five minutes; a row that still differs after three comparisons is recorded as a mismatch.

## Sampling

The row-by-row sample covers one percent of the invoice and payment rows, chosen at random for each run, and every row written in the last 24 hours. The sample is drawn again for every run so that two runs never check exactly the same rows. A run takes about 40 minutes on the full database. During the migration rehearsals the team runs it after every rehearsal and keeps the reports in the Atlas folder. The validation reports list every mismatch with the table, the primary key and the columns that differ, but never the values of those columns, because the reports are shared more widely than the billing data itself.

## Acceptance

The migration is accepted only after two consecutive runs without any mismatch. A mismatch in the payment table stops the migration until the finance lead has reviewed it, whatever its cause.
