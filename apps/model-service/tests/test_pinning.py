import pytest

from model_service.embedding import pinned_snapshot


def test_loads_only_the_pinned_revision(tmp_path):
    snapshots = tmp_path / "models--org--model" / "snapshots"
    (snapshots / "aaa").mkdir(parents=True)

    assert pinned_snapshot(str(tmp_path), "org/model", "aaa") == snapshots / "aaa"
    with pytest.raises(RuntimeError, match="org/model at revision bbb is not in"):
        pinned_snapshot(str(tmp_path), "org/model", "bbb")
