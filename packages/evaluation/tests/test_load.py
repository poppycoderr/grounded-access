from pathlib import Path

from ga_eval import cli
from ga_eval import dataset as ds

DATA = Path(__file__).resolve().parents[3] / "data"


class RecordingClient:
    """Accepts every ingestion job and lists the documents it was told are already loaded."""

    def __init__(self, loaded: set[str]) -> None:
        self._loaded = loaded
        self.jobs: list[list[str]] = []

    def list_chunks(self, token: str, **options: object) -> tuple[str, list[dict]]:
        return "abac/1", [{"documentKey": key} for key in sorted(self._loaded)]

    def ingest(self, token: str, documents: list[dict]) -> dict:
        self.jobs.append([d["key"] for d in documents])
        return {"jobId": "job", "attempts": 1, "created": 0, "updated": 0, "unchanged": 0, "chunks": 0}


def test_an_empty_database_gets_every_version_oldest_first():
    data = ds.load(DATA)
    client = RecordingClient(set())

    assert cli._load(data, client) == 0

    submitted = [key for job in client.jobs for key in job]
    for key, versions in data.current_version.items():
        assert submitted.count(key) == versions, key


def test_a_loaded_document_gets_only_its_current_version():
    data = ds.load(DATA)
    client = RecordingClient(set(data.current_version))

    assert cli._load(data, client) == 0

    submitted = [key for job in client.jobs for key in job]
    assert sorted(submitted) == sorted(data.current_version)
    assert len(client.jobs) == len(data.manifests)
