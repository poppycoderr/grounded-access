"""Keeps the model service busy with passage embeddings: ingests distinct synthetic documents into a separate tenant that no evaluation
principal can see, for a fixed time. Prints how many documents were ingested."""

import random
import sys
import time
from pathlib import Path

import httpx

from ga_eval import tokens

data, base, seconds = Path(sys.argv[1]), sys.argv[2], float(sys.argv[3])
token = tokens.mint(data, "load-admin", {"tenant_id": "loadtest", "clearance": "public"}, scope="admin")
words = "policy employee travel budget review approval security incident payment invoice customer region quarter report".split()
client = httpx.Client(base_url=base, timeout=120, headers={"Authorization": f"Bearer {token}"})
deadline = time.monotonic() + seconds
done, n = 0, 0
while time.monotonic() < deadline:
    documents = []
    for _ in range(20):
        n += 1
        text = "\n\n".join(" ".join(random.choice(words) for _ in range(120)) + "." for _ in range(4))
        documents.append({"key": f"synthetic-{n}", "title": f"Synthetic {n}", "content": text, "format": "text"})
    job = client.post("/api/v1/ingestion-jobs", json={"documents": documents}).json()
    location = f"/api/v1/ingestion-jobs/{job['jobId']}"
    while (status := client.get(location).json())["status"] not in ("succeeded", "failed") and time.monotonic() < deadline + 60:
        time.sleep(0.5)
    done += status.get("processed", 0)
print(f"ingested {done} documents ({n} submitted) in {seconds:.0f}s")
