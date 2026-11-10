# Public API Versioning Policy

This fictional policy explains how Northstar Cloud versions its public API and how long old versions keep working. This version replaces the earlier policy and extends the support period.

## Version names

A version is named by its release date, for example `2026-04-01`. Clients choose a version with the `Northstar-Version` header; a request without the header uses the version that was current when the API key was created.

## Breaking changes

Removing a field, renaming a field, adding a required parameter and changing an error code are breaking changes and only happen in a new version. Adding an optional field to a response is not a breaking change, and clients must ignore fields they do not know.

## Deprecation

A version is supported for twelve months after a newer version is released. Customers receive an email six months, three months and one month before their version stops working, and responses on a deprecated version carry a `Sunset` header with the date.
