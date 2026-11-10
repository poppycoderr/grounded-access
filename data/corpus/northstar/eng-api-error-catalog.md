# Public API Error Catalog

This fictional catalog lists the error codes that the Northstar Cloud public API returns, so that customers and support agents can tell what went wrong from a single response.

## Format of an error

Every error response carries a machine-readable code, a short message and the identifier of the request:

```json
{"error": {"code": "NSC_INVOICE_ALREADY_FINALIZED", "message": "The invoice cannot be changed.", "request_id": "req_01J9Z3K8W2M4T7Q6XB5D"}}
```

A request identifier always starts with `req_` followed by 20 letters and digits. Quote it when contacting support; it is the only way to find the request in the logs.

## Error codes

| Code | HTTP status | Meaning |
|---|---|---|
| `NSC_INVOICE_ALREADY_FINALIZED` | 409 | The invoice was finalized and can no longer be edited. |
| `NSC_IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY` | 422 | The same idempotency key was sent with a different request body. |
| `NSC_PAYMENT_METHOD_EXPIRED` | 402 | The card or mandate on the account has expired. |
| `NSC_WEBHOOK_SIGNATURE_MISMATCH` | 400 | A webhook test request was sent with a signature that does not match the endpoint secret. |
| `NSC_REGION_NOT_ENABLED` | 403 | The account has not enabled the region named in the request. |

## Idempotency keys

A client may send an `Idempotency-Key` header on any request that creates something. The API remembers each key for 24 hours. A second request with the same key and the same body returns the first response again; the same key with a different body is rejected with `NSC_IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY`.

## Deprecated codes

The code `NSC_GENERIC_CONFLICT` was replaced by more specific codes and is no longer returned. Clients should treat any code they do not recognize as a permanent failure and must not retry it automatically.
