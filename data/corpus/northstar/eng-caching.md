# Caching Guidelines

This fictional guide is part of the Northstar Cloud engineering handbook.

## What may be cached

Responses that differ per customer are cached only with the customer account in the cache key. Responses that contain personal data are not cached at the edge.

## Expiry

Every cache entry has an expiry. Entries without one are rejected by the cache client library.

## Invalidation

Services publish an invalidation event when they change cached data, rather than waiting for entries to expire.
