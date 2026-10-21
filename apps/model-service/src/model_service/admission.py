"""Admission of model work, by lane (ADR-0010). Each lane has a fixed number of computations that may run at once and a bounded number of
requests that may wait for one. A request whose lane is full is refused at once; a request whose caller's deadline passes while it waits is
refused without being computed. A computation that has started is never interrupted.

The lanes are the service's whole capacity, however many control-plane processes call it.
"""

import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field


class Refused(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code, self.message = code, message


@dataclass
class Admitted:
    """How long the request waited for its slot; the caller sets compute time when it is done."""

    queue_ms: float
    started: float = field(default_factory=time.monotonic)

    def compute_ms(self) -> float:
        return (time.monotonic() - self.started) * 1000


class Lane:
    def __init__(self, name: str, slots: int, queue: int, default_wait_seconds: float) -> None:
        if slots < 1 or queue < 0:
            raise ValueError(f"lane {name} needs at least one slot and a queue of zero or more")
        self.name = name
        self._slots = threading.BoundedSemaphore(slots)
        self._queue = queue
        self._default_wait = default_wait_seconds
        self._waiting = 0
        self._lock = threading.Lock()

    @contextmanager
    def admit(self, deadline_ms: int | None) -> Iterator[Admitted]:
        wait = self._default_wait if deadline_ms is None else deadline_ms / 1000
        if wait <= 0:
            raise Refused("DEADLINE_EXCEEDED", f"the caller's deadline had passed before the {self.name} request arrived")
        started = time.monotonic()
        if not self._slots.acquire(blocking=False):
            with self._lock:
                if self._waiting >= self._queue:
                    raise Refused("BUSY", f"the {self.name} lane is full; try again later")
                self._waiting += 1
            try:
                acquired = self._slots.acquire(timeout=wait)
            finally:
                with self._lock:
                    self._waiting -= 1
            if not acquired:
                raise Refused("DEADLINE_EXCEEDED", f"no {self.name} slot became free before the caller's deadline")
        try:
            yield Admitted(queue_ms=(time.monotonic() - started) * 1000)
        finally:
            self._slots.release()


@dataclass(frozen=True)
class Lanes:
    query: Lane
    passage: Lane
    rerank: Lane
