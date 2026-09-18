# Contracts

Wire contracts between the control plane and the model service.

| File | Owner | Checked by |
|---|---|---|
| `model-service.openapi.json` | `apps/model-service` (generated from its Pydantic types) | `test_committed_contract_matches_the_implementation` in the model service; control-plane client tests |

To change the contract, edit the model-service schemas and regenerate:

```bash
cd apps/model-service && uv run python -m model_service.contract > ../../packages/contracts/model-service.openapi.json
```

Breaking changes need a new path version (`/v2/...`), not an edit in place.
