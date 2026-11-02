"""Study API. Run from the repository root:

    uvicorn --factory server.app.main:create_app --port 8000
"""

from __future__ import annotations

import json
import random
import secrets
import sqlite3
import uuid
import zlib
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from typing import Iterator

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response

from . import assignment
from .config import ServerSettings, StudyConfig, load_server_settings, load_study_config
from .db import connect, init_db
from .schemas import (
    AssignResponse,
    CompleteResponse,
    ConsentRequest,
    EnrollRequest,
    EnrollResponse,
    RatingsRequest,
    TaskLogRequest,
    UsageRequest,
    ValuationsRequest,
    VideosRequest,
)

BASELINE = "baseline"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def _transaction(conn: sqlite3.Connection) -> Iterator[None]:
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")


def create_app(
    config: StudyConfig | None = None,
    settings: ServerSettings | None = None,
    rng: random.Random | None = None,
) -> FastAPI:
    config = config or load_study_config()
    settings = settings or load_server_settings()
    rng = rng or random.SystemRandom()

    conn = connect(settings.db_path)
    init_db(conn)

    app = FastAPI(title="Feed Study API (prototype)")
    app.state.conn = conn

    def participant(authorization: str = Header(default="")) -> sqlite3.Row:
        token = authorization.removeprefix("Bearer ").strip()
        row = conn.execute("SELECT * FROM participants WHERE token = ?", (token,)).fetchone()
        if not token or row is None:
            raise HTTPException(status_code=401, detail="Unknown participant token")
        return row

    @app.get("/health")
    def health() -> dict:
        return {"ok": True, "dev_mode": config.dev_mode}

    @app.post("/v1/enroll", response_model=EnrollResponse)
    def enroll(request: EnrollRequest) -> EnrollResponse:
        with _transaction(conn):
            row = conn.execute(
                "SELECT participant_id, token FROM participants WHERE prolific_id = ?",
                (request.prolific_id,),
            ).fetchone()
            if row is None:
                participant_id, token = str(uuid.uuid4()), secrets.token_urlsafe(32)
                conn.execute(
                    "INSERT INTO participants "
                    "(participant_id, prolific_id, token, app_version, android_sdk, enrolled_at) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        participant_id,
                        request.prolific_id,
                        token,
                        request.app_version,
                        request.android_sdk,
                        _now(),
                    ),
                )
            else:
                participant_id, token = row["participant_id"], row["token"]
        return EnrollResponse(participant_id=participant_id, token=token, config=config)

    @app.post("/v1/consent", status_code=204)
    def consent(request: ConsentRequest, who: sqlite3.Row = Depends(participant)) -> Response:
        conn.execute(
            "UPDATE participants SET consent_version = ?, consented_at_millis = ? "
            "WHERE participant_id = ?",
            (request.consent_version, request.accepted_at_millis, who["participant_id"]),
        )
        return Response(status_code=204)

    @app.post("/v1/videos", status_code=204)
    def videos(request: VideosRequest, who: sqlite3.Row = Depends(participant)) -> Response:
        pid = who["participant_id"]
        with _transaction(conn):
            s = request.summary
            conn.execute(
                "INSERT OR REPLACE INTO export_summaries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    pid,
                    request.round,
                    s.watch_count,
                    s.like_count,
                    s.favorite_count,
                    s.dropped_no_video_id,
                    s.dropped_bad_date,
                    s.ignored_other_records,
                    s.window_days,
                ),
            )
            conn.executemany(
                "INSERT OR REPLACE INTO videos VALUES (?, ?, ?, ?)",
                [(pid, v.window, v.video_id, v.watch_count) for v in request.videos],
            )
        return Response(status_code=204)

    @app.post("/v1/ratings", status_code=204)
    def ratings(request: RatingsRequest, who: sqlite3.Row = Depends(participant)) -> Response:
        pid = who["participant_id"]
        with _transaction(conn):
            conn.executemany(
                "INSERT OR REPLACE INTO ratings VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    (
                        pid,
                        request.round,
                        r.video_id,
                        r.position,
                        r.status,
                        None if r.wants_to_stop is None else int(r.wants_to_stop),
                        r.watched_seconds,
                        r.error_code,
                    )
                    for r in request.ratings
                ],
            )
        return Response(status_code=204)

    @app.post("/v1/valuations", status_code=204)
    def valuations(request: ValuationsRequest, who: sqlite3.Row = Depends(participant)) -> Response:
        pid = who["participant_id"]
        with _transaction(conn):
            conn.executemany(
                "INSERT OR REPLACE INTO valuations VALUES (?, ?, ?, ?, ?)",
                [
                    (pid, request.round, v.good, v.position, v.min_accept_price)
                    for v in request.valuations
                ],
            )
            if request.filter_wtp is not None:
                conn.execute(
                    "UPDATE participants SET filter_wtp = ? WHERE participant_id = ?",
                    (request.filter_wtp, pid),
                )
        return Response(status_code=204)

    @app.post("/v1/assign", response_model=AssignResponse)
    def assign(who: sqlite3.Row = Depends(participant)) -> AssignResponse:
        """Idempotent: a retry returns the first result and cannot redraw the arm."""
        pid = who["participant_id"]
        with _transaction(conn):
            row = conn.execute(
                "SELECT eligible, task_video_ids FROM participants WHERE participant_id = ?",
                (pid,),
            ).fetchone()
            if row["eligible"] is not None:
                return AssignResponse(
                    eligible=bool(row["eligible"]),
                    task_video_ids=json.loads(row["task_video_ids"] or "[]"),
                    hide_creator=config.hide_creator,
                )

            rated = conn.execute(
                "SELECT video_id, status, wants_to_stop FROM ratings "
                "WHERE participant_id = ? AND round = ?",
                (pid, BASELINE),
            ).fetchall()
            answered = [r for r in rated if r["status"] == "rated"]
            if not rated:
                raise HTTPException(status_code=409, detail="No baseline ratings yet")
            if not answered:
                # No video in the sample would play. Recorded, so the pilot can count these.
                conn.execute(
                    "UPDATE participants SET n_rated = 0, n_flagged = 0, eligible = 0, k_i = 0, "
                    "task_video_ids = '[]', assigned_at = ? WHERE participant_id = ?",
                    (_now(), pid),
                )
                return AssignResponse(
                    eligible=False, task_video_ids=[], hide_creator=config.hide_creator
                )

            flagged = [r["video_id"] for r in answered if r["wants_to_stop"]]
            unavailable = {r["video_id"] for r in rated if r["status"] == "unavailable"}
            pool = [
                r["video_id"]
                for r in conn.execute(
                    "SELECT video_id FROM videos WHERE participant_id = ? AND window = ?",
                    (pid, "baseline_pool"),
                )
            ]
            h = len(flagged) / len(answered)
            plan = assignment.plan_task(
                k=config.k,
                flagged=flagged,
                unavailable=unavailable,
                watched=pool + [r["video_id"] for r in answered],
                control_arm=config.control_arm,
                control_draw=config.control_draw,
            )

            arm, stratum, chosen = None, None, []
            if plan.eligible:
                stratum = assignment.stratum_for(h, settings.h_strata_cuts)
                arm = assignment.next_arm(conn, stratum, settings.block_size, rng)
                chosen = assignment.choose_videos(plan, arm, config.control_arm, rng)

            conn.execute(
                "UPDATE participants SET h_baseline = ?, n_rated = ?, n_flagged = ?, stratum = ?, "
                "arm = ?, eligible = ?, k_i = ?, task_video_ids = ?, assigned_at = ? "
                "WHERE participant_id = ?",
                (
                    h,
                    len(answered),
                    len(flagged),
                    stratum,
                    arm,
                    int(plan.eligible),
                    plan.k_i,
                    json.dumps(chosen),
                    _now(),
                    pid,
                ),
            )
        return AssignResponse(
            eligible=plan.eligible, task_video_ids=chosen, hide_creator=config.hide_creator
        )

    @app.post("/v1/task-log", status_code=204)
    def task_log(request: TaskLogRequest, who: sqlite3.Row = Depends(participant)) -> Response:
        pid = who["participant_id"]
        with _transaction(conn):
            conn.executemany(
                "INSERT OR REPLACE INTO task_log VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    (
                        pid,
                        i.video_id,
                        i.position,
                        i.handoff_at_millis,
                        i.returned_at_millis,
                        i.handoff_package,
                        i.self_report,
                        i.foreground_seconds,
                    )
                    for i in request.items
                ],
            )
        return Response(status_code=204)

    @app.post("/v1/usage", status_code=204)
    def usage(request: UsageRequest, who: sqlite3.Row = Depends(participant)) -> Response:
        pid = who["participant_id"]
        with _transaction(conn):
            conn.executemany(
                "INSERT OR REPLACE INTO usage_days VALUES (?, ?, ?, ?, ?, ?)",
                [
                    (pid, r.day, r.package, r.source, r.foreground_seconds, r.sessions)
                    for r in request.rows
                ],
            )
            conn.execute(
                "UPDATE participants SET usage_access_granted = ?, "
                "earliest_event_day = COALESCE(?, earliest_event_day), "
                "earliest_daily_stats_day = COALESCE(?, earliest_daily_stats_day) "
                "WHERE participant_id = ?",
                (
                    int(request.usage_access_granted),
                    request.earliest_event_day,
                    request.earliest_daily_stats_day,
                    pid,
                ),
            )
        return Response(status_code=204)

    @app.post("/v1/complete", response_model=CompleteResponse)
    def complete(who: sqlite3.Row = Depends(participant)) -> CompleteResponse:
        pid = who["participant_id"]
        with _transaction(conn):
            row = conn.execute(
                "SELECT completion_code FROM participants WHERE participant_id = ?", (pid,)
            ).fetchone()
            code = row["completion_code"] or secrets.token_hex(4).upper()
            conn.execute(
                "UPDATE participants SET completion_code = ?, "
                "completed_at = COALESCE(completed_at, ?) WHERE participant_id = ?",
                (code, _now(), pid),
            )
        return CompleteResponse(completion_code=code)

    if config.dev_mode:
        _add_dev_routes(app, conn, config, settings, participant)
    return app


def _add_dev_routes(
    app: FastAPI,
    conn: sqlite3.Connection,
    config: StudyConfig,
    settings: ServerSettings,
    participant,
) -> None:
    """Serves synthetic exports. These routes do not exist unless dev_mode is on."""
    from fixtures.generate_export import (
        baseline_export_zip,
        endline_export_zip,
        utc_from_millis,
    )

    @app.get("/dev/export")
    def dev_export(
        round: str = Query(pattern="^(baseline|endline)$"),
        now_millis: int = Query(),
        enrolled_at_millis: int | None = Query(default=None),
        treated_at_millis: int | None = Query(default=None),
        who: sqlite3.Row = Depends(participant),
    ) -> Response:
        seed = zlib.crc32(f"{who['participant_id']}:{round}".encode())
        now = utc_from_millis(now_millis)
        if round == BASELINE:
            body = baseline_export_zip(
                now=now,
                lookback=timedelta(days=config.lookback_days),
                n_watches=max(60, config.rating_sample_size * 4),
                harmful_share=settings.dev_harmful_share,
                seed=seed,
            )
        else:
            if enrolled_at_millis is None or treated_at_millis is None:
                raise HTTPException(status_code=422, detail="Endline needs enrolment and treatment times")
            effect = settings.dev_treatment_effect if who["arm"] == assignment.TREATMENT else 0.0
            body = endline_export_zip(
                enrolled_at=utc_from_millis(enrolled_at_millis),
                treated_at=utc_from_millis(treated_at_millis),
                now=now,
                n_per_window=max(60, config.rating_sample_size * 4),
                harmful_share_pre=settings.dev_harmful_share,
                harmful_share_post=settings.dev_harmful_share + effect,
                task_video_ids=json.loads(who["task_video_ids"] or "[]"),
                seed=seed,
            )
        return Response(content=body, media_type="application/zip")

