import pytest

from ga_eval import stats


def test_bootstrap_of_a_constant_is_that_constant():
    interval = stats.bootstrap([0.5] * 20)

    assert (interval.mean, interval.low, interval.high) == (0.5, 0.5, 0.5)


def test_bootstrap_interval_contains_the_mean_and_is_reproducible():
    values = [0.0, 1.0] * 15

    first, second = stats.bootstrap(values), stats.bootstrap(values)

    assert first.low < first.mean < first.high
    assert first == second


def test_paired_difference_of_identical_runs_is_not_a_difference():
    a = [0.0, 1.0, 0.5, 1.0] * 5

    assert stats.verdict(stats.paired(a, a)) == "no detectable difference"


def test_a_consistent_gain_on_every_case_is_detected():
    a = [0.2, 0.4, 0.6, 0.3] * 10

    difference = stats.paired(a, [x + 0.3 for x in a])

    assert stats.verdict(difference) == "better"
    assert difference.mean == pytest.approx(0.3)


def test_a_gain_from_a_single_case_is_not_enough():
    a = [0.0] * 15
    b = [0.0] * 14 + [1.0]

    assert stats.verdict(stats.paired(a, b)) == "no detectable difference"


def test_paired_comparison_needs_the_same_cases():
    with pytest.raises(ValueError):
        stats.paired([1.0], [1.0, 0.0])
