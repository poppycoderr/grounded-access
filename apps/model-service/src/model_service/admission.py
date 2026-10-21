"""Admission of model work, by lane (ADR-0010). Each lane has a fixed number of computations that may run at once and a bounded number of
requests that may wait for one. A request whose lane is full is refused at once; a request whose deadline passes before its computation
starts is refused without being computed. A computation that has started is never interrupted.

Admission happens on the event loop, before any worker thread is taken: only admitted work runs in the thread pool, so waiting requests never
hold threads and the health check never waits for one (review 2026-10-21, N3). A request's deadline counts from the moment it arrived, and a
caller cannot extend it beyond its lane's longest wait.

The lanes are the service's whole capacity, however many control-plane processes call it.
"""

import asyncio
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field


class Refused(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code, self.message = code, message


@dataclass
class Admitted:
    """How long the request waited for its slot, and the moment after which its model computation may no longer start."""

    queue_ms: float
    deadline: float
    lane: str
    started: float = field(default_factory=time.monotonic)

    def compute_ms(self) -> float:
        return (time.monotonic() - self.started) * 1000

    def check(self) -> None:
        """Called after preparation (validation, token counting) and before the model runs."""
        if time.monotonic() > self.deadline:
            raise Refused("DEADLINE_EXCEEDED", f"the caller's deadline passed while the {self.lane} request was being prepared")


class Lane:
    def __init__(self, name: str, slots: int, queue: int, default_wait_seconds: float) -> None:
        if slots < 1 or queue < 0:
            raise ValueError(f"lane {name} needs at least one slot and a queue of zero or more")
        self.name = name
        self.slots = slots
        self.queue = queue
        self._semaphore = asyncio.Semaphore(slots)
        self._longest_wait = default_wait_seconds
        self._waiting = 0

    @asynccontextmanager
    async def admit(self, deadline_ms: int | None, received: float) -> AsyncIterator[Admitted]:
        """`received` is the monotonic time the request arrived; `deadline_ms` the caller's remaining wait at that moment, capped by the lane's
        longest wait (which also applies when the caller sends none)."""
        wait = self._longest_wait if deadline_ms is None else min(deadline_ms / 1000, self._longest_wait)
        deadline = received + wait
        if time.monotonic() >= deadline:
            raise Refused("DEADLINE_EXCEEDED", f"the caller's deadline had passed before the {self.name} request was admitted")
        started = time.monotonic()
        if self._semaphore.locked():
            if self._waiting >= self.queue:
                raise Refused("BUSY", f"the {self.name} lane is full; try again later")
            self._waiting += 1
            try:
                await asyncio.wait_for(self._semaphore.acquire(), deadline - time.monotonic())
            except TimeoutError as late:
                raise Refused("DEADLINE_EXCEEDED", f"no {self.name} slot became free before the caller's deadline") from late
            finally:
                self._waiting -= 1
        else:
            await self._semaphore.acquire()
        try:
            yield Admitted(queue_ms=(time.monotonic() - started) * 1000, deadline=deadline, lane=self.name)
        finally:
            self._semaphore.release()


@dataclass(frozen=True)
class Lanes:
    query: Lane
    passage: Lane
    rerank: Lane
    tokenize: Lane

    def threads(self) -> int:
        """Worker threads admitted work can occupy at most."""
        return self.query.slots + self.passage.slots + self.rerank.slots + self.tokenize.slots
