from model_service.reranking import BATCH_SIZE, length_batches


def test_batches_hold_every_passage_once_shortest_first():
    lengths = [300, 12, 40, 12, 380, 25, 60, 18, 90]

    batches = length_batches(lengths, size=4)

    assert [len(batch) for batch in batches] == [4, 4, 1]
    assert sorted(i for batch in batches for i in batch) == list(range(len(lengths)))
    flat = [lengths[i] for batch in batches for i in batch]
    assert flat == sorted(lengths)


def test_one_long_passage_shares_its_batch_only_with_the_next_longest():
    lengths = [20] * 19 + [380]

    batches = length_batches(lengths)

    assert 19 in batches[-1]
    assert all(19 not in batch for batch in batches[:-1])
    assert max(len(batch) for batch in batches) == BATCH_SIZE


def test_equal_lengths_keep_their_order_and_no_passages_make_no_batches():
    assert length_batches([5, 5, 5], size=2) == [[0, 1], [2]]
    assert length_batches([]) == []
