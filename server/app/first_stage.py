"""First stage: harmful-content share before and after treatment.

Applies one fixed, person-specific classifier, built from baseline ratings, to both windows.
With the stub extractor this only tests the data pipeline.

    python -m server.app.first_stage [path/to/feedstudy.sqlite3]
"""

from __future__ import annotations

import json
import sqlite3
import sys
from dataclasses import asdict, dataclass

import numpy as np

from .db import connect
from .features import FeatureExtractor, StubFeatureExtractor


class NearestNeighbours:
    """Share of the k nearest rated videos that the person flagged, by cosine similarity."""

    def __init__(self, embeddings: np.ndarray, flags: np.ndarray, k: int = 5) -> None:
        self.embeddings = _unit(embeddings)
        self.flags = flags
        self.k = min(k, len(flags))

    def is_harmful(self, embeddings: np.ndarray) -> np.ndarray:
        similarity = _unit(embeddings) @ self.embeddings.T
        nearest = np.argpartition(-similarity, self.k - 1, axis=1)[:, : self.k]
        return self.flags[nearest].mean(axis=1) >= 0.5


def _unit(matrix: np.ndarray) -> np.ndarray:
    return matrix / np.linalg.norm(matrix, axis=1, keepdims=True)


@dataclass(frozen=True)
class ParticipantFirstStage:
    participant_id: str
    arm: str
    h_pre: float
    h_post: float
    delta_h: float
    n_pre: int
    n_post: int


def _window_share(
    conn: sqlite3.Connection,
    participant_id: str,
    window: str,
    classifier: NearestNeighbours,
    extractor: FeatureExtractor,
) -> tuple[float | None, int]:
    rows = conn.execute(
        "SELECT video_id, watch_count FROM videos WHERE participant_id = ? AND window = ?",
        (participant_id, window),
    ).fetchall()
    if not rows:
        return None, 0
    embeddings = np.stack([extractor.embed(row["video_id"]) for row in rows])
    weights = np.array([row["watch_count"] for row in rows], dtype=float)
    harmful = classifier.is_harmful(embeddings)
    return float((harmful * weights).sum() / weights.sum()), int(weights.sum())


def participant_first_stage(
    conn: sqlite3.Connection,
    participant_id: str,
    arm: str,
    extractor: FeatureExtractor,
) -> ParticipantFirstStage | None:
    rated = conn.execute(
        "SELECT video_id, wants_to_stop FROM ratings "
        "WHERE participant_id = ? AND round = 'baseline' AND status = 'rated'",
        (participant_id,),
    ).fetchall()
    if not rated:
        return None

    classifier = NearestNeighbours(
        np.stack([extractor.embed(row["video_id"]) for row in rated]),
        np.array([bool(row["wants_to_stop"]) for row in rated]),
    )
    h_pre, n_pre = _window_share(conn, participant_id, "pre", classifier, extractor)
    h_post, n_post = _window_share(conn, participant_id, "post", classifier, extractor)
    if h_pre is None or h_post is None:
        return None
    return ParticipantFirstStage(participant_id, arm, h_pre, h_post, h_post - h_pre, n_pre, n_post)


def compute_first_stage(conn: sqlite3.Connection, extractor: FeatureExtractor) -> dict:
    people = conn.execute(
        "SELECT participant_id, arm FROM participants WHERE arm IS NOT NULL"
    ).fetchall()
    results = [
        result
        for row in people
        if (result := participant_first_stage(conn, row["participant_id"], row["arm"], extractor))
    ]

    def arm_mean(arm: str) -> float | None:
        values = [r.delta_h for r in results if r.arm == arm]
        return float(np.mean(values)) if values else None

    treatment, control = arm_mean("treatment"), arm_mean("control")
    return {
        "warning": "Stub features: this tests the pipeline and says nothing about real videos.",
        "n_participants": len(results),
        "mean_delta_h_treatment": treatment,
        "mean_delta_h_control": control,
        "first_stage_effect": (
            treatment - control if treatment is not None and control is not None else None
        ),
        "participants": [asdict(r) for r in results],
    }


def main() -> None:
    path = sys.argv[1] if len(sys.argv) > 1 else "server/data/feedstudy.sqlite3"
    print(json.dumps(compute_first_stage(connect(path), StubFeatureExtractor()), indent=2))


if __name__ == "__main__":
    main()
