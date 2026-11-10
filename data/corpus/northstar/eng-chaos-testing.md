# Chaos Testing in Staging

This fictional guide is part of the Northstar Cloud engineering handbook.

## Automated faults

A chaos job in staging stops one random instance of every service once per working day.

## Expectations

Every service is expected to keep serving requests while one instance is gone.

## Opting out

A service may opt out for at most two weeks, for example during a large migration.
