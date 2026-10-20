import math

from ga_eval import metrics
from ga_eval.dataset import Span
from ga_eval.metrics import Result

SPAN = Span("policy", 1, 100, 150)


def result(rank: int, document: str = "policy", version: int = 1, start: int = 90, end: int = 160) -> Result:
    return Result(document, version, start, end, rank)


def test_a_result_covers_a_span_only_on_the_same_document_version_with_overlap():
    assert metrics.covers(result(1), SPAN)
    assert metrics.covers(result(1, start=149, end=300), SPAN)
    assert not metrics.covers(result(1, start=150, end=300), SPAN)
    assert not metrics.covers(result(1, version=2), SPAN)
    assert not metrics.covers(result(1, document="other"), SPAN)


def test_recall_counts_spans_covered_within_the_cutoff():
    second = Span("policy", 1, 400, 450)
    results = [result(1), result(7, start=400, end=420)]

    assert metrics.recall_at(5, results, [SPAN, second]) == 0.5
    assert metrics.recall_at(10, results, [SPAN, second]) == 1.0


def test_reciprocal_rank_uses_the_first_relevant_result():
    assert metrics.reciprocal_rank([result(1, document="other"), result(3)], [SPAN]) == 1 / 3
    assert metrics.reciprocal_rank([result(1, document="other")], [SPAN]) == 0.0


def test_violations_list_every_document_outside_the_visible_set():
    results = [result(1), result(2, document="secret"), result(3, document="secret")]

    assert metrics.violations(results, {"policy"}, {"policy": 1, "secret": 1}) == ["secret"]


def test_a_chunk_of_a_replaced_version_is_a_violation_even_for_a_visible_document():
    assert metrics.violations([result(1)], {"policy"}, {"policy": 2}) == ["policy@v1"]


def test_ndcg_rewards_early_evidence_and_ignores_duplicate_coverage():
    second = Span("policy", 1, 400, 450)

    perfect = metrics.ndcg_at(10, [result(1), result(2, start=400, end=420)], [SPAN, second])
    duplicated = metrics.ndcg_at(10, [result(1), result(2), result(3, start=400, end=420)], [SPAN, second])

    assert perfect == 1.0
    assert 0 < duplicated < perfect


# The worked examples of ADR-0008, with the version 1 nDCG next to each. Two spans of one document, 100-150 and 400-450.
SECOND = Span("policy", 1, 400, 450)


def test_evidence_coverage_scores_one_chunk_covering_both_spans_at_rank_one_as_perfect():
    both = [result(1, start=90, end=460)]

    assert metrics.evidence_coverage_at(10, both, [SPAN, SECOND]) == 1.0
    assert round(metrics.ndcg_at(10, both, [SPAN, SECOND]), 3) == 0.613


def test_evidence_coverage_discounts_the_span_found_second():
    split = [result(1), result(2, start=400, end=420)]

    assert round(metrics.evidence_coverage_at(10, split, [SPAN, SECOND]), 3) == 0.815
    assert metrics.ndcg_at(10, split, [SPAN, SECOND]) == 1.0


def test_evidence_coverage_counts_only_the_first_hit_of_a_span():
    duplicated = [result(1, document="other"), result(2, document="other"), result(3), result(4)]

    assert metrics.evidence_coverage_at(10, duplicated, [SPAN]) == 0.5
    assert metrics.evidence_coverage_at(10, duplicated[:3], [SPAN]) == 0.5
    assert metrics.ndcg_at(10, duplicated, [SPAN]) == 0.5


def test_evidence_coverage_gives_nothing_for_a_span_found_below_the_cutoff():
    other = Span("handbook", 1, 0, 50)
    results = (
        [result(1, document="x"), result(2)] + [result(rank, document="x") for rank in range(3, 12)] + [result(12, document="handbook", start=0)]
    )

    assert round(metrics.evidence_coverage_at(10, results, [SPAN, other]), 3) == 0.315
    assert round(metrics.ndcg_at(10, results, [SPAN, other]), 3) == 0.387
    assert round(metrics.evidence_coverage_at(12, results, [SPAN, other]), 3) == round((1 / math.log2(3) + 1 / math.log2(13)) / 2, 3)


def test_evidence_coverage_ignores_chunks_of_another_version():
    assert metrics.evidence_coverage_at(10, [result(1, version=2)], [SPAN]) == 0.0
    assert metrics.evidence_coverage_at(10, [], [SPAN]) == 0.0


def test_hard_negative_rank_and_first_relevant_rank():
    results = [result(1, document="tempting"), result(2)]

    assert metrics.hard_negative_rank(results, {"tempting"}) == 1
    assert metrics.first_relevant_rank(results, [SPAN]) == 2
    assert metrics.hard_negative_rank(results, {"absent"}) is None
