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

    assert metrics.violations(results, {"policy"}) == ["secret"]
