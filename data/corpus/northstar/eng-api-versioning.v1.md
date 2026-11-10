# Public API Versioning Policy

This fictional policy explains how Northstar Cloud versions its public API and how long old versions keep working.

## Version names

A version is named by its release date, for example `2026-04-01`. Clients choose a version with the `Northstar-Version` header; a request without the header uses the version that was current when the API key was created.

## Breaking changes

Removing a field, renaming a field, adding a required parameter and changing an error code are breaking changes and only happen in a new version.

## Deprecation

A version is supported for six months after a newer version is released. Customers receive an email three months and one month before their version stops working.
