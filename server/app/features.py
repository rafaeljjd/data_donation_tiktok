"""Video features. The prototype has only a stub, so its output says nothing about real videos."""

from __future__ import annotations

import hashlib
from typing import Protocol

import numpy as np

STUB_DIMENSIONS = 16
N_TOPICS = 4


class FeatureExtractor(Protocol):
    def embed(self, video_id: str) -> np.ndarray: ...


class StubFeatureExtractor:
    """Synthetic embeddings: a topic centre taken from the ID, plus noise seeded by the ID."""

    def __init__(self, noise: float = 0.15) -> None:
        self.noise = noise
        centres = np.random.default_rng(0).normal(size=(N_TOPICS, STUB_DIMENSIONS))
        self.centres = centres / np.linalg.norm(centres, axis=1, keepdims=True)

    def embed(self, video_id: str) -> np.ndarray:
        seed = int.from_bytes(hashlib.sha256(video_id.encode()).digest()[:8], "big")
        noise = np.random.default_rng(seed).normal(size=STUB_DIMENSIONS)
        return self.centres[int(video_id) % N_TOPICS] + self.noise * noise
