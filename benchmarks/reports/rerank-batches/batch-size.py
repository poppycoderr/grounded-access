"""Times sorted batches of 1 to 20 on two synthetic pools cut from the Northstar corpus: 20 passages of 180 words, and 19 of 40 words with one of
270. Run inside apps/model-service: `cat ../../data/corpus/northstar/*.md > all.txt; uv run python batch-size.py all.txt`."""

import sys
import time

from model_service.settings import Settings
from model_service.reranking import FastEmbedReranker
s = Settings.from_env()
r = FastEmbedReranker(s.reranker_model, s.reranker_revision, s.model_cache_dir)
q = "How many paid volunteer days do EU employees receive?"
words = open(sys.argv[1]).read().split()
def cut(n, off): return " ".join(words[off:off+n])
def t(label, pool, bs):
    def run():
        order = sorted(range(len(pool)), key=lambda i: r.tokens.count(q, pool[i]))
        for k in range(0, len(order), bs):
            list(r._model.rerank(q, [pool[i] for i in order[k:k+bs]], batch_size=bs))
    run(); a = time.perf_counter(); run(); run(); print(label, f"bs={bs}", f"{(time.perf_counter()-a)/2:.2f}s")
uniform = [cut(180, 7*i) for i in range(20)]
print("uniform tokens", max(r.tokens.count(q, p) for p in uniform))
for bs in (1, 2, 4, 8, 20): t("20x180w", uniform, bs)
mixed = [cut(40, 5*i) for i in range(19)] + [cut(270, 0)]
print("mixed long tokens", r.tokens.count(q, mixed[-1]))
for bs in (1, 4, 8, 20): t("19x40w+1x270w", mixed, bs)
