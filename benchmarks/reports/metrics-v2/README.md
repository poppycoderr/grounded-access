# First run with metrics version 2

The CI `smoke` job of commit `b878bb5` on `main` (GitHub-hosted runner, AMD EPYC 7763, 4 processors), 2026-10-21, unchanged from its `evaluation-results` artifact. It is the first published run that reports evidence coverage (EC@10) instead of nDCG@10 (ADR-0008), records a manifest, and was indexed with chunker release 4 (ADR-0009).

- **Same rankings.** Every ranked list of every strategy and case equals the one in [`heading-context`](../heading-context/), the last version-1 run, so Recall@5, Recall@10 and MRR@10 are unchanged. This was checked by comparing the two `cases.jsonl` files. `ga-eval compare` refuses the pair, because their metric versions differ.
- **What changed.** Only the reported metric, the manifest, the keyword ranking check (passed) and the chunker version label (`markdown/4`).
- **Gates.** 0 security violations, 0 scope failures, no repeated degraded requests.
