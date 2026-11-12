"""Times the cross-encoder on one candidate pool, given as {"query": ..., "texts": [...]}: one batch, sorted batches of four, one passage at a
time. Run inside apps/model-service (`uv run python pool-timing.py pool.json`) or in its container."""

import json
import sys
import time

from model_service.settings import Settings
from model_service.reranking import FastEmbedReranker, length_batches
s = Settings.from_env(); r = FastEmbedReranker(s.reranker_model, s.reranker_revision, s.model_cache_dir)
d = json.load(open(sys.argv[1])); q, ps = d["query"], d["texts"]
lens = [r.tokens.count(q, p) for p in ps]; print("pair tokens", sorted(lens), "sum", sum(lens))
def t(label, fn):
    fn(); a = time.perf_counter(); fn(); fn(); print(label, f"{(time.perf_counter()-a)/2:.2f}s")
t("one batch", lambda: list(r._model.rerank(q, ps)))
t("sorted batches of 4", lambda: r.score(q, ps))
t("one at a time", lambda: [list(r._model.rerank(q, [p])) for p in ps])
