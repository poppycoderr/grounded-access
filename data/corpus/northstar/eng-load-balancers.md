# Load Balancers

This fictional standard is part of the Northstar Cloud engineering handbook.

## Health checks

Load balancers check each instance every ten seconds. An instance that fails three checks in a row stops receiving traffic.

## Draining

An instance that is shut down keeps serving its open connections for up to 60 seconds before it is removed.

## Timeouts

The idle timeout for client connections is 120 seconds, and requests that take longer than 30 seconds are cut off with an error.
