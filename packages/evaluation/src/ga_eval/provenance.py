"""The manifest of a run (ADR-0008): what was measured, read from the system itself, and whether it stayed the same until the last request.

The evaluator's own checkout says nothing about the server it talks to, so the server describes itself (`GET /api/v1/system`) and the corpus
is fingerprinted from what the evaluation principals can list. Nothing missing is filled in from the evaluator's side.
"""

import hashlib
import importlib.metadata
import json
import platform
import subprocess
from pathlib import Path

from ga_eval.client import ApiClient

MANIFEST_VERSION = "1"

# A change in any of these between the first and the last request means the run measured two systems.
DRIFT_FIELDS = {
    "server commit": ("server", "build", "commit"),
    "index profile": ("server", "indexProfile"),
    "reranker": ("server", "reranker"),
    "corpus": ("corpus", "digest"),
}


def corpus_digest(listings: dict[str, tuple[str, list[dict]]]) -> str:
    """SHA-256 over every chunk each principal can list: who lists it, document, version, chunker version and offsets. A reload, a new
    version, a label change or a re-chunking all change it."""
    rows = sorted(
        (principal, c["documentKey"], c["versionNo"], c["chunkerVersion"], c["charStart"], c["charEnd"])
        for principal, (_, chunks) in listings.items()
        for c in chunks
    )
    return hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()


def snapshot(client: ApiClient, token: str, listings: dict[str, tuple[str, list[dict]]]) -> dict:
    """The parts of the manifest that are read from the system, so they can be read again at the end and compared."""
    return {
        "server": client.system(token),
        "corpus": {"digest": corpus_digest(listings), "chunks": sum(len(chunks) for _, chunks in listings.values())},
    }


def manifest(observed: dict, dataset_version: str, dataset_digest: str, root: Path, run: dict) -> dict:
    return {
        "manifest_version": MANIFEST_VERSION,
        "dataset": {"version": dataset_version, "digest": dataset_digest},
        **observed,
        "evaluator": evaluator(root),
        "run": run,
    }


def drift(start: dict, end: dict) -> list[str]:
    """What changed between two snapshots, by name; empty when nothing that defines the system did."""
    return [name for name, path in DRIFT_FIELDS.items() if _at(start, path) != _at(end, path)]


def differences(before: dict, after: dict) -> list[dict]:
    """Every leaf of two manifests that differs, by dotted path. The run date and the evaluator's dirty flag are listed like anything else:
    they describe the experiment too."""
    flat_before, flat_after = _flatten(before), _flatten(after)
    return [
        {"field": field, "before": flat_before.get(field), "after": flat_after.get(field)}
        for field in sorted(flat_before.keys() | flat_after.keys())
        if flat_before.get(field) != flat_after.get(field)
    ]


def evaluator(root: Path) -> dict:
    return {
        "git_sha": _git(root, "rev-parse", "--short", "HEAD") or "unknown",
        "uncommitted_changes": bool(_git(root, "status", "--porcelain")),
        "ga_eval_version": importlib.metadata.version("ga-eval"),
        "cpu": cpu_model(),
        "platform": platform.platform(),
    }


def cpu_model() -> str:
    """The evaluator's CPU. The server reports its own in the manifest; in the published CI runs both are the same machine."""
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.is_file():
        for line in cpuinfo.read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    return platform.processor() or platform.machine()


def _git(root: Path, *args: str) -> str | None:
    try:
        return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def _at(document: dict, path: tuple[str, ...]):
    value = document
    for key in path:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def _flatten(value, prefix: str = "") -> dict:
    if isinstance(value, dict):
        flat: dict = {}
        for key, inner in value.items():
            flat |= _flatten(inner, f"{prefix}.{key}" if prefix else key)
        return flat
    return {prefix: value}
