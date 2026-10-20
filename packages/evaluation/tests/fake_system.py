"""What a test double answers for GET /api/v1/system."""

import copy

SYSTEM = {
    "build": {"version": "0.1.0-SNAPSHOT", "commit": "abc1234"},
    "indexProfile": {
        "id": "00000000-0000-0000-0000-000000000001",
        "embeddingModel": "BAAI/bge-small-en-v1.5",
        "embeddingRevision": "rev1",
        "dimensions": 384,
        "queryPrefix": "q: ",
        "passagePrefix": "",
        "embeddingContext": True,
        "chunkerRelease": "3",
        "chunkMaxWords": 180,
        "chunkOverlapWords": 30,
    },
    "reranker": {"model": "reranker", "revision": "rev2"},
    "retrieval": {"candidates": 50, "rrfK": 60, "rerankCandidates": 20, "sparseContext": True, "rerankContext": False},
    "host": {"cpu": "Test CPU", "processors": 4},
}


def system() -> dict:
    return copy.deepcopy(SYSTEM)
