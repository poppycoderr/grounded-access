# Schema Migration Guidelines

This fictional guide is part of the Northstar Cloud engineering handbook.

## Backward compatibility

A migration must work with the version of the code that is running before it and the version that follows it. Columns are added as nullable first and made required in a later release.

## Large tables

Migrations that rewrite a table with more than ten million rows are run in batches by a background job, not in the deployment.

## Removing columns

A column is removed only after the code that reads it has been gone from production for at least two weeks.
