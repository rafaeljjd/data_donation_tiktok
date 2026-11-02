"""Everything the app may send. Unknown fields are rejected, so raw export data cannot slip in."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from .config import StudyConfig

Round = Literal["baseline", "endline"]
VIDEO_ID_PATTERN = r"^\d{6,25}$"


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EnrollRequest(Strict):
    prolific_id: str = Field(min_length=1, max_length=64)
    app_version: str = Field(max_length=32)
    android_sdk: int


class EnrollResponse(Strict):
    participant_id: str
    token: str
    config: StudyConfig


class ConsentRequest(Strict):
    consent_version: str = Field(max_length=64)
    accepted_at_millis: int


class VideoRow(Strict):
    video_id: str = Field(pattern=VIDEO_ID_PATTERN)
    window: Literal["baseline_pool", "pre", "post"]
    watch_count: int = Field(ge=1)


class ExportSummary(Strict):
    """Counts only. There are no links, titles or timestamps."""

    watch_count: int
    like_count: int
    favorite_count: int
    dropped_no_video_id: int
    dropped_bad_date: int
    ignored_other_records: int
    window_days: float | None = None


class VideosRequest(Strict):
    round: Round
    summary: ExportSummary
    videos: list[VideoRow]


class RatingRow(Strict):
    video_id: str = Field(pattern=VIDEO_ID_PATTERN)
    position: int
    status: Literal["rated", "unavailable"]
    wants_to_stop: bool | None = None
    watched_seconds: float = 0
    error_code: str | None = Field(default=None, max_length=32)


class RatingsRequest(Strict):
    round: Round
    ratings: list[RatingRow]


class ValuationRow(Strict):
    good: Literal["tiktok", "instagram", "both"]
    position: int
    min_accept_price: float | None = None  # None means no listed price was acceptable.


class ValuationsRequest(Strict):
    round: Round
    valuations: list[ValuationRow]
    filter_wtp: float | None = None


class AssignResponse(Strict):
    """Holds no arm label. The app only learns which videos to show."""

    eligible: bool
    task_video_ids: list[str]
    hide_creator: bool


class TaskLogRow(Strict):
    video_id: str = Field(pattern=VIDEO_ID_PATTERN)
    position: int
    handoff_at_millis: int | None = None
    returned_at_millis: int | None = None
    handoff_package: str | None = Field(default=None, max_length=128)
    self_report: Literal["sent", "not_found", "skipped"] | None = None
    foreground_seconds: float | None = None


class TaskLogRequest(Strict):
    items: list[TaskLogRow]


class UsageRow(Strict):
    day: str = Field(max_length=32)
    package: str = Field(max_length=128)
    foreground_seconds: int = Field(ge=0)
    sessions: int | None = None
    source: Literal["events", "daily_stats"]


class UsageRequest(Strict):
    rows: list[UsageRow]
    earliest_event_day: str | None = Field(default=None, max_length=32)
    earliest_daily_stats_day: str | None = Field(default=None, max_length=32)
    usage_access_granted: bool = True


class CompleteResponse(Strict):
    completion_code: str
