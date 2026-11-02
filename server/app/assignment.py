"""Stratified randomization and the choice of task videos."""

from __future__ import annotations

import bisect
import json
import random
import sqlite3
from dataclasses import dataclass

TREATMENT = "treatment"
CONTROL = "control"


def stratum_for(h: float, cuts: list[float]) -> int:
    """Index of the baseline-h stratum. A value on a cut goes to the higher stratum."""
    return bisect.bisect_right(cuts, h)


def next_arm(conn: sqlite3.Connection, stratum: int, block_size: int, rng: random.Random) -> str:
    """Permuted blocks within a stratum. The caller holds the write transaction."""
    row = conn.execute(
        "SELECT remaining FROM randomization_blocks WHERE stratum = ?", (stratum,)
    ).fetchone()
    remaining = json.loads(row["remaining"]) if row else []
    if not remaining:
        remaining = [TREATMENT, CONTROL] * (block_size // 2)
        rng.shuffle(remaining)
    arm = remaining.pop()
    conn.execute(
        "INSERT INTO randomization_blocks (stratum, remaining) VALUES (?, ?) "
        "ON CONFLICT(stratum) DO UPDATE SET remaining = excluded.remaining",
        (stratum, json.dumps(remaining)),
    )
    return arm


@dataclass(frozen=True)
class TaskPlan:
    eligible: bool
    k_i: int
    control_pool: list[str]
    flagged: list[str]


def plan_task(
    *,
    k: int,
    flagged: list[str],
    unavailable: set[str],
    watched: list[str],
    control_arm: str,
    control_draw: str,
) -> TaskPlan:
    """Fixes the number of videos before randomization, so it cannot differ by arm."""
    flagged_ok = sorted(set(flagged) - unavailable)
    watched_ok = set(watched) - unavailable

    if control_arm == "no_signal":
        control_pool: list[str] = []
        k_i = min(k, len(flagged_ok))
    else:
        if control_draw == "unflagged":
            watched_ok -= set(flagged_ok)
        control_pool = sorted(watched_ok)
        k_i = min(k, len(flagged_ok), len(control_pool))
    return TaskPlan(eligible=k_i > 0, k_i=k_i, control_pool=control_pool, flagged=flagged_ok)


def choose_videos(plan: TaskPlan, arm: str, control_arm: str, rng: random.Random) -> list[str]:
    if arm == TREATMENT:
        return rng.sample(plan.flagged, plan.k_i)
    if control_arm == "no_signal":
        return []
    return rng.sample(plan.control_pool, plan.k_i)
