"""Study settings. Open design decisions live here, so changing one needs no app release.

Values marked PLACEHOLDER were chosen for the prototype and are not design decisions.
"""

from __future__ import annotations

import json
import os
from typing import Literal

from pydantic import BaseModel, ConfigDict

TIKTOK_PACKAGES = ["com.zhiliaoapp.musically", "com.ss.android.ugc.trill"]
INSTAGRAM_PACKAGES = ["com.instagram.android"]
CHROME = "com.android.chrome"


class StudyConfig(BaseModel):
    """Settings sent to the app."""

    model_config = ConfigDict(extra="forbid")

    config_version: str = "prototype-1"
    consent_version: str = "placeholder-not-irb-approved"

    # Treatment design
    k: int = 10  # PLACEHOLDER
    rating_sample_size: int = 50  # PLACEHOLDER
    lookback_days: int = 28  # PLACEHOLDER: the doc says "recent weeks"
    min_watch_seconds: int = 3  # PLACEHOLDER
    control_arm: Literal["random_signal", "no_signal"] = "random_signal"
    control_draw: Literal["all_watched", "unflagged"] = "all_watched"
    hide_creator: bool = False  # PLACEHOLDER

    # Valuations
    price_list: list[float] = [1, 2, 5, 10, 20, 35, 50, 75, 100]  # PLACEHOLDER, no payout
    currency_symbol: str = "$"
    deactivation_days: int = 7  # PLACEHOLDER

    # Timing. day_seconds below 86400 compresses the study for testing.
    tracking_days: int = 28
    day_seconds: int = 86400
    reminder_interval_seconds: int = 86400
    sync_interval_minutes: int = 720
    usage_safety_margin_seconds: int = 600
    compliance_read_delay_seconds: int = 900

    # Android
    tracked_packages: list[str] = TIKTOK_PACKAGES + INSTAGRAM_PACKAGES
    handoff_packages: list[str] = TIKTOK_PACKAGES
    # Unverified: the phone test decides which URL shape opens the exact video.
    deep_link_template: str = "https://www.tiktok.com/@/video/{id}"

    # Development only
    dev_mode: bool = False
    synthetic_player: bool = False


class ServerSettings(BaseModel):
    """Settings that stay on the server, so the app cannot infer anything about assignment."""

    model_config = ConfigDict(extra="forbid")

    h_strata_cuts: list[float] = [0.1, 0.25]  # PLACEHOLDER: h below 10%, 10-25%, 25% and above
    block_size: int = 4
    db_path: str = "server/data/feedstudy.sqlite3"
    dev_harmful_share: float = 0.3
    dev_treatment_effect: float = -0.15  # Injected into synthetic second exports.


def load_study_config() -> StudyConfig:
    overrides = json.loads(os.environ.get("FEEDSTUDY_CONFIG", "{}"))
    return StudyConfig(**overrides)


def load_server_settings() -> ServerSettings:
    overrides = json.loads(os.environ.get("FEEDSTUDY_SERVER", "{}"))
    return ServerSettings(**overrides)


def emulator_dev_config() -> dict:
    """Compressed clock and synthetic videos. Chrome stands in for TikTok on the emulator."""
    return {
        "k": 3,
        "rating_sample_size": 12,
        "lookback_days": 28,
        "min_watch_seconds": 0,
        "tracking_days": 28,
        "day_seconds": 3,
        "reminder_interval_seconds": 30,
        "usage_safety_margin_seconds": 0,
        "compliance_read_delay_seconds": 5,
        "tracked_packages": TIKTOK_PACKAGES + INSTAGRAM_PACKAGES + [CHROME],
        "handoff_packages": TIKTOK_PACKAGES + [CHROME],
        "dev_mode": True,
        "synthetic_player": True,
    }
