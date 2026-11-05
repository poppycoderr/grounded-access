"""The chunking tokenizer identity (ADR-0012) changes with the files that decide token counts, and with nothing else."""

from pathlib import Path

from conftest import WordTokens

from model_service.tokens import TOKENIZER_FILES, file_digest, identity


def snapshot(directory: Path, tokenizer: str, config: str) -> list[Path]:
    directory.mkdir()
    for name, content in zip(TOKENIZER_FILES, (tokenizer, config), strict=True):
        (directory / name).write_text(content)
    return [directory / name for name in TOKENIZER_FILES]


def test_a_tokenizer_digest_changes_with_either_file_and_with_bytes_moved_between_them(tmp_path):
    base = file_digest(snapshot(tmp_path / "base", '{"model": 1}', '{"max_length": 512}'))

    assert file_digest(snapshot(tmp_path / "same", '{"model": 1}', '{"max_length": 512}')) == base
    assert file_digest(snapshot(tmp_path / "vocabulary", '{"model": 2}', '{"max_length": 512}')) != base
    assert file_digest(snapshot(tmp_path / "limit", '{"model": 1}', '{"max_length": 256}')) != base
    assert file_digest(snapshot(tmp_path / "moved", '{"model": 1}{', '"max_length": 512}')) != base


def test_the_identity_names_both_tokenizers_in_the_order_they_are_applied():
    embedding, reranker = WordTokens(8, digest="a"), WordTokens(8, digest="b")

    assert identity([embedding, reranker]) == identity([WordTokens(4, digest="a"), WordTokens(4, digest="b")])
    assert identity([embedding, reranker]) != identity([reranker, embedding])
    assert identity([embedding, reranker]) != identity([embedding, WordTokens(8, digest="c")])
