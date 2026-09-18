# Public API Rate Limits

The Northstar Cloud public API enforces fictional rate limits per API key.

## Default limits

The Standard plan allows 600 requests per minute. The Enterprise plan allows 3,000 requests per minute and bursts of up to 5,000 requests in a ten-second window.

## When a limit is exceeded

The API responds with HTTP 429 and a Retry-After header in seconds. Clients should back off exponentially and must not retry sooner than the header allows.
