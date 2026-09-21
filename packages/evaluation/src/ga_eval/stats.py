"""Bootstrap confidence intervals over evaluation cases, and paired comparisons between two configurations on the same cases."""

import random
from dataclasses import dataclass

SAMPLES = 10_000
SEED = 20260921


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
    rng = random.Random(seed)
    n = len(values)
    means = sorted(sum(values[rng.randrange(n)] for _ in range(n)) / n for _ in range(samples))
    return Interval(sum(values) / n, means[int(0.025 * samples)], means[int(0.975 * samples) - 1])


def paired(a: list[float], b: list[float], samples: int = SAMPLES, seed: int = SEED) -> Interval:
    """Interval of the mean per-case difference b - a. Pairing removes the variance that comes from some cases simply being harder."""
    if len(a) != len(b):
        raise ValueError("paired comparison needs the same cases on both sides")
    return bootstrap([y - x for x, y in zip(a, b, strict=True)], samples, seed)


def verdict(difference: Interval) -> str:
    if not difference.excludes_zero():
        return "no detectable difference"
    return "better" if difference.mean > 0 else "worse"
