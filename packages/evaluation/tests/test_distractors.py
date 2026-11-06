"""Generated distractors (v0.2 plan, step 1a): deterministic, labelled like real documents, never confused with the labelled corpus, and with
a visibility statement written apart from the policy compiler."""

from pathlib import Path

import yaml

from ga_eval import dataset as ds
from ga_eval import distractors

ROOT = Path(__file__).resolve().parents[3]
DATA = ds.load(ROOT / "data")


def test_the_same_seed_writes_the_same_bytes_and_another_seed_does_not(tmp_path):
    for name, seed in (("a", 7), ("b", 7), ("c", 8)):
        distractors.write(distractors.generate(seed, 40, DATA.principals), tmp_path / name)

    def files(directory: Path) -> dict[str, bytes]:
        return {str(p.relative_to(directory)): p.read_bytes() for p in sorted(directory.rglob("*")) if p.is_file()}

    assert files(tmp_path / "a") == files(tmp_path / "b")
    assert files(tmp_path / "a") != files(tmp_path / "c")


def test_keys_never_collide_with_the_labelled_corpus_and_say_what_they_are():
    generated = distractors.generate(1, 200, DATA.principals)
    labelled = {d.key for m in DATA.manifests for d in m.documents}

    assert len({d.key for d in generated.documents}) == 200
    assert all(d.key.startswith("distractor-") for d in generated.documents)
    assert not labelled & {d.key for d in generated.documents}


def test_labels_cover_every_rule_and_a_selective_project_owns_a_small_share():
    documents = distractors.generate(1, 1000, DATA.principals).documents

    assert {d.tenant for d in documents} == {"northstar", "external"}
    assert {d.classification for d in documents} == {"public", "internal", "confidential", "restricted"}
    assert any(d.allowed_departments for d in documents)
    selective = [d for d in documents if d.required_projects == ["atlas"]]
    assert 0.01 <= len(selective) / len(documents) <= 0.03


def test_some_sections_are_long_enough_that_release_4_has_to_split_them():
    documents = distractors.generate(1, 100, DATA.principals).documents
    sections = [s for d in documents for s in d.content.split("\n## ")]

    assert max(len(s.split()) for s in sections) > 400
    assert sum(len(d.content.split()) for d in documents) / len(documents) > 500


def test_visibility_follows_the_decision_table_written_out_independently():
    claims = DATA.principals
    tenant_doc = distractors.Document("distractor-x", "external", "t", "c", "public", [], [])
    confidential = distractors.Document("distractor-y", "northstar", "t", "c", "confidential", [], [])
    support_only = distractors.Document("distractor-z", "northstar", "t", "c", "internal", ["support"], [])
    atlas = distractors.Document("distractor-w", "northstar", "t", "c", "internal", [], ["atlas"])

    assert not distractors.visible(claims["alice-engineer"], tenant_doc)
    assert distractors.visible(claims["mallory-outsider"], tenant_doc)
    assert distractors.visible(claims["carol-manager"], confidential)
    assert not distractors.visible(claims["alice-engineer"], confidential)
    assert distractors.visible(claims["bob-support"], support_only)
    assert not distractors.visible(claims["alice-engineer"], support_only)
    assert distractors.visible(claims["alice-engineer"], atlas)
    assert not distractors.visible(claims["bob-support"], atlas)
    assert not distractors.visible(claims["northstar-admin"], tenant_doc), "admins search nothing"


def test_the_written_set_loads_as_manifests_and_lists_visibility_per_principal(tmp_path):
    generated = distractors.generate(3, 60, DATA.principals)
    distractors.write(generated, tmp_path)

    manifests = [ds.Manifest.model_validate(yaml.safe_load(p.read_text())) for p in sorted((tmp_path / "manifests").glob("*.yaml"))]
    assert sum(len(m.documents) for m in manifests) == 60
    for manifest in manifests:
        for document in manifest.documents:
            assert (tmp_path / document.file).is_file()
    visibility = yaml.safe_load((tmp_path / "visibility.yaml").read_text())["principals"]
    for principal, keys in visibility.items():
        expected = sorted(d.key for d in generated.documents if distractors.visible(DATA.principals[principal], d))
        assert keys == expected
    assert "northstar-admin" not in visibility
