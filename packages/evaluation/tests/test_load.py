from pathlib import Path

from ga_eval import cli, distractors
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

    assert cli._load(data, client, data.manifests, data.root) == 0

    submitted = [key for job in client.jobs for key in job]
    for key, versions in data.current_version.items():
        assert submitted.count(key) == versions, key


def test_a_loaded_document_gets_only_its_current_version():
    data = ds.load(DATA)
    client = RecordingClient(set(data.current_version))

    assert cli._load(data, client, data.manifests, data.root) == 0

    submitted = [key for job in client.jobs for key in job]
    assert sorted(submitted) == sorted(data.current_version)
    assert max(len(job) for job in client.jobs) <= cli.LOAD_BATCH
    assert len(client.jobs) == sum(-(-len(m.documents) // cli.LOAD_BATCH) for m in data.manifests)


def test_generated_distractors_load_from_their_own_directory_in_bounded_jobs(tmp_path):
    data = ds.load(DATA)
    distractors.write(distractors.generate(5, 450, data.principals), tmp_path)
    client = RecordingClient(set())

    assert cli._load(data, client, ds.load_manifests(tmp_path), tmp_path) == 0

    assert max(len(job) for job in client.jobs) <= cli.LOAD_BATCH
    assert sorted(key for job in client.jobs for key in job) == [f"distractor-{n:05d}" for n in range(450)]
