"""SQLite storage. Every upload is an upsert, so a retried request cannot duplicate data."""

from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS participants (
    participant_id TEXT PRIMARY KEY,
    prolific_id TEXT NOT NULL UNIQUE,
    token TEXT NOT NULL UNIQUE,
    app_version TEXT,
    android_sdk INTEGER,
    enrolled_at TEXT NOT NULL,
    consent_version TEXT,
    consented_at_millis INTEGER,
    h_baseline REAL,
    n_rated INTEGER,
    n_flagged INTEGER,
    stratum INTEGER,
    arm TEXT,
    eligible INTEGER,
    k_i INTEGER,
    task_video_ids TEXT,
    assigned_at TEXT,
    filter_wtp REAL,
    usage_access_granted INTEGER,
    earliest_event_day TEXT,
    earliest_daily_stats_day TEXT,
    completion_code TEXT,
    completed_at TEXT
);
CREATE TABLE IF NOT EXISTS export_summaries (
    participant_id TEXT NOT NULL,
    round TEXT NOT NULL,
    watch_count INTEGER,
    like_count INTEGER,
    favorite_count INTEGER,
    dropped_no_video_id INTEGER,
    dropped_bad_date INTEGER,
    ignored_other_records INTEGER,
    window_days REAL,
    PRIMARY KEY (participant_id, round)
);
CREATE TABLE IF NOT EXISTS videos (
    participant_id TEXT NOT NULL,
    window TEXT NOT NULL,
    video_id TEXT NOT NULL,
    watch_count INTEGER NOT NULL,
    PRIMARY KEY (participant_id, window, video_id)
);
CREATE TABLE IF NOT EXISTS ratings (
    participant_id TEXT NOT NULL,
    round TEXT NOT NULL,
    video_id TEXT NOT NULL,
    position INTEGER,
    status TEXT NOT NULL,
    wants_to_stop INTEGER,
    watched_seconds REAL,
    error_code TEXT,
    PRIMARY KEY (participant_id, round, video_id)
);
CREATE TABLE IF NOT EXISTS valuations (
    participant_id TEXT NOT NULL,
    round TEXT NOT NULL,
    good TEXT NOT NULL,
    position INTEGER,
    min_accept_price REAL,
    PRIMARY KEY (participant_id, round, good)
);
CREATE TABLE IF NOT EXISTS task_log (
    participant_id TEXT NOT NULL,
    video_id TEXT NOT NULL,
    position INTEGER,
    handoff_at_millis INTEGER,
    returned_at_millis INTEGER,
    handoff_package TEXT,
    self_report TEXT,
    foreground_seconds REAL,
    PRIMARY KEY (participant_id, video_id)
);
CREATE TABLE IF NOT EXISTS usage_days (
    participant_id TEXT NOT NULL,
    day TEXT NOT NULL,
    package TEXT NOT NULL,
    source TEXT NOT NULL,
    foreground_seconds INTEGER NOT NULL,
    sessions INTEGER,
    PRIMARY KEY (participant_id, day, package, source)
);
CREATE TABLE IF NOT EXISTS randomization_blocks (
    stratum INTEGER PRIMARY KEY,
    remaining TEXT NOT NULL
);
"""


def connect(path: str) -> sqlite3.Connection:
    if path != ":memory:":
        Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 5000")
    if path != ":memory:":
        conn.execute("PRAGMA journal_mode = WAL")
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
