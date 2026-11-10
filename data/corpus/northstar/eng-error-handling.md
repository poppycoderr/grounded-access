# Error Handling

This fictional guide is part of the Northstar Cloud engineering handbook.

## Retries

Calls to other services are retried at most three times with exponential backoff and jitter, and only when the call is safe to repeat.

## Timeouts

Every outgoing call has a timeout that is shorter than the timeout of the request that caused it.

## Failing open or closed

A dependency that is not needed for correctness, such as a recommendation service, fails open. A dependency that decides access fails closed.
