"""Bootstrap confidence intervals over evaluation cases, and paired comparisons between two configurations on the same cases."""

import random
from dataclasses import dataclass

SAMPLES = 10_000
SEED = 20260921
SIGNIFICANCE = 0.05


@dataclass(frozen=True)
class Interval:
    mean: float
    low: float
    high: float

    def excludes_zero(self) -> bool:
        return self.low > 0 or self.high < 0

    def render(self, digits: int = 3) -> str:
        return f"{self.mean:.{digits}f} [{self.low:.{digits}f}, {self.high:.{digits}f}]"


def bootstrap(values: list[float], samples: int = SAMPLES, seed: int = SEED) -> Interval:
    """Percentile bootstrap of the mean, resampling cases with replacement; a fixed seed keeps reports reproducible."""
    if not values:
        return Interval(0.0, 0.0, 0.0)
    means = _resampled_means(values, samples, seed)
    return Interval(sum(values) / len(values), means[int(0.025 * samples)], means[int(0.975 * samples) - 1])


def p_value(values: list[float], samples: int = SAMPLES, seed: int = SEED) -> float:
    """Two-sided bootstrap p-value for a mean of zero: twice the smaller share of resampled means on either side of zero. It uses the same
    resamples as `bootstrap`, so p < 0.05 agrees with an interval that excludes zero up to the resolution of the samples."""
    if not values:
        return 1.0
    means = _resampled_means(values, samples, seed)
    below = sum(1 for m in means if m <= 0) / samples
    above = sum(1 for m in means if m >= 0) / samples
    return min(1.0, 2 * min(below, above))


def holm(p_values: list[float]) -> list[float]:
    """Holm-adjusted p-values, in the order given. Controls the chance of any false finding across the whole family (ADR-0016)."""
    order = sorted(range(len(p_values)), key=lambda i: p_values[i])
    adjusted = [1.0] * len(p_values)
    running = 0.0
    for rank, i in enumerate(order):
        running = max(running, min(1.0, (len(p_values) - rank) * p_values[i]))
        adjusted[i] = running
    return adjusted


def adjusted_verdict(mean: float, adjusted_p: float) -> str:
    if adjusted_p >= SIGNIFICANCE:
        return "no detectable difference"
    return "better" if mean > 0 else "worse"


def _resampled_means(values: list[float], samples: int, seed: int) -> list[float]:
    rng = random.Random(seed)
    n = len(values)
    return sorted(sum(values[rng.randrange(n)] for _ in range(n)) / n for _ in range(samples))


def paired(a: list[float], b: list[float], samples: int = SAMPLES, seed: int = SEED) -> Interval:
    """Interval of the mean per-case difference b - a. Pairing removes the variance that comes from some cases simply being harder."""
    if len(a) != len(b):
        raise ValueError("paired comparison needs the same cases on both sides")
    return bootstrap([y - x for x, y in zip(a, b, strict=True)], samples, seed)


def verdict(difference: Interval) -> str:
    if not difference.excludes_zero():
        return "no detectable difference"
    return "better" if difference.mean > 0 else "worse"
