#!/usr/bin/env python3
"""Generate synthetic TikTok exports. No real participant data is involved.

TikTok does not document its JSON nesting, so the "modern" and "legacy" shapes are based on
public write-ups and must be checked against a real export.

Synthetic video IDs carry a topic: topic = int(video_id) % 4. Topic 0 stands for content a
participant wants to avoid. The server's stub feature extractor uses the same rule.

Writes the parser's test fixtures when run directly:
    python3 fixtures/generate_export.py
"""

from __future__ import annotations

import io
import json
import random
import zipfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

N_TOPICS = 4
HARMFUL_TOPIC = 0
BASE_ID = 7_400_000_000_000_000_000
DATE_FORMAT = "%Y-%m-%d %H:%M:%S"


def video_id(rng: random.Random, topic: int) -> str:
    """A 19-digit ID whose remainder encodes the topic."""
    raw = BASE_ID + rng.randrange(10**15) * N_TOPICS
    return str(raw - raw % N_TOPICS + topic)


def topic_of(vid: str) -> int:
    return int(vid) % N_TOPICS


def draw_topic(rng: random.Random, harmful_share: float) -> int:
    if rng.random() < harmful_share:
        return HARMFUL_TOPIC
    return rng.randrange(1, N_TOPICS)


def video_url(vid: str) -> str:
    return f"https://www.tiktokv.com/share/video/{vid}/"


@dataclass(frozen=True)
class Watch:
    video_id: str
    watched_at: datetime


def spread_watches(
    rng: random.Random,
    n: int,
    start: datetime,
    end: datetime,
    harmful_share: float,
) -> list[Watch]:
    """n watches of distinct synthetic videos, evenly spaced between start and end."""
    if n <= 0:
        return []
    step = (end - start) / n
    return [
        Watch(video_id(rng, draw_topic(rng, harmful_share)), start + step * i + step / 2)
        for i in range(n)
    ]


def _record(watch: Watch, date_key: str = "Date", link_key: str = "Link") -> dict:
    return {
        date_key: watch.watched_at.strftime(DATE_FORMAT),
        link_key: video_url(watch.video_id),
    }


def _other_sections() -> dict:
    """Sections the app must skip. The text below must never reach the server."""
    return {
        "Direct Messages": {
            "Chat History": {
                "Chat History with synthetic_friend:": [
                    {"Date": "2026-01-01 10:00:00", "From": "synthetic_friend",
                     "Content": "SYNTHETIC-PRIVATE-MESSAGE"}
                ]
            }
        },
        "Profile": {
            "Profile Information": {
                "ProfileMap": {"userName": "SYNTHETIC-USERNAME", "emailAddress": "synthetic@example.com"}
            }
        },
    }


def modern_export(watches: list[Watch], likes: list[Watch], favorites: list[Watch]) -> dict:
    return {
        "Your Activity": {
            "Watch History": {"VideoList": [_record(w) for w in watches]},
            "Searches": {"SearchList": [{"Date": "2026-01-01 10:00:00", "SearchTerm": "SYNTHETIC-SEARCH"}]},
        },
        "Likes and Favorites": {
            "Like List": {"ItemFavoriteList": [_record(w, "date", "link") for w in likes]},
            "Favorite Videos": {"FavoriteVideoList": [_record(w) for w in favorites]},
        },
        "Post": {"Posts": {"VideoList": [
            {"Date": "2026-01-01 10:00:00",
             "Link": "https://www.tiktokv.com/share/video/7399999999999999999/", "Likes": "3"}
        ]}},
        **_other_sections(),
    }


def legacy_export(watches: list[Watch], likes: list[Watch], favorites: list[Watch]) -> dict:
    return {
        "Activity": {
            "Video Browsing History": {"VideoList": [_record(w) for w in watches]},
            "Like List": {"ItemFavoriteList": [_record(w) for w in likes]},
            "Favorite Videos": {"FavoriteVideoList": [_record(w) for w in favorites]},
        },
        **_other_sections(),
    }


def to_zip(export: dict, name: str = "TikTok_Data_synthetic/user_data_tiktok.json") -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(name, json.dumps(export))
    return buffer.getvalue()


def txt_zip() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("TikTok_Data_synthetic/Activity/Browsing History.txt", "Date: 2026-01-01\nLink: x\n")
    return buffer.getvalue()


def utc_from_millis(millis: int) -> datetime:
    return datetime.fromtimestamp(millis / 1000, tz=timezone.utc)


def baseline_export_zip(
    *,
    now: datetime,
    lookback: timedelta,
    n_watches: int,
    harmful_share: float,
    seed: int,
) -> bytes:
    """First export: watches over the lookback period, ending just before now."""
    rng = random.Random(seed)
    watches = spread_watches(rng, n_watches, now - lookback, now, harmful_share)
    likes = rng.sample(watches, k=min(5, len(watches)))
    return to_zip(modern_export(watches, likes, []))


def endline_export_zip(
    *,
    enrolled_at: datetime,
    treated_at: datetime,
    now: datetime,
    n_per_window: int,
    harmful_share_pre: float,
    harmful_share_post: float,
    task_video_ids: list[str],
    seed: int,
) -> bytes:
    """Second export: equal-length pre and post windows, plus the task videos' own views."""
    rng = random.Random(seed)
    length = now - treated_at
    pre = spread_watches(rng, n_per_window, enrolled_at - length, enrolled_at, harmful_share_pre)
    post = spread_watches(rng, n_per_window, treated_at, now, harmful_share_post)
    # Opening a task video may add it to watch history, so the fixture includes those views.
    task_views = [
        Watch(vid, treated_at + timedelta(seconds=i % 5)) for i, vid in enumerate(task_video_ids)
    ]
    last = [Watch(video_id(rng, 1), now)]  # Marks the export's cut-off.
    return to_zip(modern_export(pre + post + task_views + last, [], []))


def write_test_fixtures(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    rng = random.Random(20261101)
    end = datetime(2026, 10, 30, 12, 0, 0, tzinfo=timezone.utc)
    watches = spread_watches(rng, 40, end - timedelta(days=28), end, harmful_share=0.2)
    likes = watches[:5]
    favorites = watches[5:8]

    (directory / "export_modern.zip").write_bytes(to_zip(modern_export(watches, likes, favorites)))
    (directory / "export_legacy.json").write_text(json.dumps(legacy_export(watches, likes, favorites)))
    (directory / "export_txt.zip").write_bytes(txt_zip())
    (directory / "export_no_watch_history.json").write_text(json.dumps(_other_sections()))


if __name__ == "__main__":
    target = Path(__file__).resolve().parent.parent / "android/app/src/test/resources/fixtures"
    write_test_fixtures(target)
    print(f"Wrote fixtures to {target}")
