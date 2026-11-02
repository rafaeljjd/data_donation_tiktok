from __future__ import annotations

import random

import numpy as np
import pytest
from fastapi.testclient import TestClient

from fixtures.generate_export import HARMFUL_TOPIC, draw_topic, topic_of, video_id
from server.app import assignment
from server.app.config import ServerSettings, StudyConfig, emulator_dev_config
from server.app.features import StubFeatureExtractor
from server.app.first_stage import NearestNeighbours, compute_first_stage
from server.app.main import create_app


def make_app(tmp_path, config: StudyConfig | None = None, **settings):
    return create_app(
        config=config or StudyConfig(),
        settings=ServerSettings(db_path=str(tmp_path / "test.sqlite3"), **settings),
        rng=random.Random(42),
    )


@pytest.fixture
def client(tmp_path):
    return TestClient(make_app(tmp_path))


def enroll(client, prolific_id="p1"):
    response = client.post(
        "/v1/enroll", json={"prolific_id": prolific_id, "app_version": "t", "android_sdk": 36}
    )
    assert response.status_code == 200
    body = response.json()
    return {"Authorization": f"Bearer {body['token']}"}, body


def numeric_ids(n, start=7_000_000):
    return [str(start + i) for i in range(n)]


def upload_baseline(client, headers, flagged, unflagged, unavailable=(), extra_pool=()):
    all_ids = [*flagged, *unflagged, *unavailable, *extra_pool]
    response = client.post(
        "/v1/videos",
        headers=headers,
        json={
            "round": "baseline",
            "summary": {
                "watch_count": len(all_ids),
                "like_count": 0,
                "favorite_count": 0,
                "dropped_no_video_id": 0,
                "dropped_bad_date": 0,
                "ignored_other_records": 0,
            },
            "videos": [{"video_id": v, "window": "baseline_pool", "watch_count": 1} for v in all_ids],
        },
    )
    assert response.status_code == 204
    rows = (
        [{"video_id": v, "position": i, "status": "rated", "wants_to_stop": True} for i, v in enumerate(flagged)]
        + [{"video_id": v, "position": i, "status": "rated", "wants_to_stop": False} for i, v in enumerate(unflagged)]
        + [{"video_id": v, "position": i, "status": "unavailable", "error_code": "1001"} for i, v in enumerate(unavailable)]
    )
    response = client.post("/v1/ratings", headers=headers, json={"round": "baseline", "ratings": rows})
    assert response.status_code == 204


def arm_of(client, body):
    row = client.app.state.conn.execute(
        "SELECT * FROM participants WHERE participant_id = ?", (body["participant_id"],)
    ).fetchone()
    return row


# --- Enrolment and authentication


def test_enroll_is_idempotent(client):
    _, first = enroll(client)
    _, again = enroll(client)
    assert first["participant_id"] == again["participant_id"]
    assert first["token"] == again["token"]


def test_config_sends_no_strata(client):
    _, body = enroll(client)
    assert "h_strata_cuts" not in body["config"]
    assert "block_size" not in body["config"]


def test_requests_need_a_token(client):
    assert client.post("/v1/assign").status_code == 401
    assert client.post("/v1/assign", headers={"Authorization": "Bearer nope"}).status_code == 401


# --- Payloads reject anything outside the schema


@pytest.mark.parametrize(
    "path,payload",
    [
        (
            "/v1/ratings",
            {"round": "baseline", "ratings": [{"video_id": "7000000", "position": 0, "status": "rated", "post_title": "x"}]},
        ),
        (
            "/v1/videos",
            {
                "round": "baseline",
                "summary": {"watch_count": 1, "like_count": 0, "favorite_count": 0, "dropped_no_video_id": 0, "dropped_bad_date": 0, "ignored_other_records": 0},
                "videos": [{"video_id": "7000000", "window": "pre", "watch_count": 1, "watched_at": "2026-01-01"}],
            },
        ),
        ("/v1/usage", {"rows": [], "chat_history": ["x"]}),
        ("/v1/task-log", {"items": [{"video_id": "7000000", "position": 0, "url": "https://x"}]}),
        (
            "/v1/valuations",
            {"round": "baseline", "valuations": [{"good": "tiktok", "position": 0, "username": "x"}]},
        ),
    ],
)
def test_rejects_unknown_fields(client, path, payload):
    headers, _ = enroll(client)
    assert client.post(path, headers=headers, json=payload).status_code == 422


def test_rejects_urls_in_video_id_field(client):
    headers, _ = enroll(client)
    response = client.post(
        "/v1/ratings",
        headers=headers,
        json={"round": "baseline", "ratings": [{"video_id": "https://www.tiktok.com/@alice/video/7000000", "position": 0, "status": "rated"}]},
    )
    assert response.status_code == 422


# --- Retries


def test_retried_uploads_do_not_duplicate(client):
    headers, body = enroll(client)
    usage = {"rows": [{"day": "2026-11-01", "package": "a", "foreground_seconds": 60, "sessions": 1, "source": "events"}]}
    assert client.post("/v1/usage", headers=headers, json=usage).status_code == 204
    usage["rows"][0]["foreground_seconds"] = 90
    assert client.post("/v1/usage", headers=headers, json=usage).status_code == 204
    rows = client.app.state.conn.execute("SELECT foreground_seconds FROM usage_days").fetchall()
    assert [r["foreground_seconds"] for r in rows] == [90]


# --- Assignment


def test_assign_needs_ratings(client):
    headers, _ = enroll(client)
    assert client.post("/v1/assign", headers=headers).status_code == 409


def test_assignment_is_idempotent(client):
    headers, body = enroll(client)
    flagged, unflagged = numeric_ids(20), numeric_ids(30, 8_000_000)
    upload_baseline(client, headers, flagged, unflagged)
    first = client.post("/v1/assign", headers=headers).json()
    arm = arm_of(client, body)["arm"]
    for _ in range(5):
        assert client.post("/v1/assign", headers=headers).json() == first
    assert arm_of(client, body)["arm"] == arm


def test_response_never_names_the_arm(client):
    headers, _ = enroll(client)
    upload_baseline(client, headers, numeric_ids(20), numeric_ids(30, 8_000_000))
    response = client.post("/v1/assign", headers=headers)
    assert set(response.json()) == {"eligible", "task_video_ids", "hide_creator"}
    assert "treatment" not in response.text and "control" not in response.text


def test_treatment_gets_flagged_and_both_arms_get_k(client):
    arms = set()
    for i in range(12):
        headers, body = enroll(client, f"p{i}")
        flagged = numeric_ids(20, 7_000_000 + i * 1000)
        unflagged = numeric_ids(30, 8_000_000 + i * 1000)
        upload_baseline(client, headers, flagged, unflagged)
        result = client.post("/v1/assign", headers=headers).json()
        arm = arm_of(client, body)["arm"]
        arms.add(arm)
        assert len(result["task_video_ids"]) == 10
        if arm == "treatment":
            assert set(result["task_video_ids"]) <= set(flagged)
        else:
            assert set(result["task_video_ids"]) <= set(flagged) | set(unflagged)
    assert arms == {"treatment", "control"}


def test_fewer_flagged_than_k_uses_flagged_count_in_both_arms(client):
    sizes = {}
    for i in range(8):
        headers, body = enroll(client, f"p{i}")
        upload_baseline(client, headers, numeric_ids(3, 7_000_000 + i * 1000), numeric_ids(47, 8_000_000 + i * 1000))
        result = client.post("/v1/assign", headers=headers).json()
        sizes.setdefault(arm_of(client, body)["arm"], set()).add(len(result["task_video_ids"]))
    assert sizes == {"treatment": {3}, "control": {3}}


def test_nothing_flagged_is_ineligible_and_uses_no_block_slot(client):
    headers, body = enroll(client)
    upload_baseline(client, headers, [], numeric_ids(50))
    result = client.post("/v1/assign", headers=headers).json()
    assert result == {"eligible": False, "task_video_ids": [], "hide_creator": False}
    row = arm_of(client, body)
    assert row["arm"] is None and row["h_baseline"] == 0
    assert client.app.state.conn.execute("SELECT COUNT(*) FROM randomization_blocks").fetchone()[0] == 0


def test_all_videos_unavailable_is_ineligible_not_stuck(client):
    headers, body = enroll(client)
    upload_baseline(client, headers, [], [], unavailable=numeric_ids(100))
    response = client.post("/v1/assign", headers=headers)
    assert response.status_code == 200
    assert response.json()["eligible"] is False
    row = arm_of(client, body)
    assert row["arm"] is None and row["n_rated"] == 0 and row["h_baseline"] is None
    assert client.post("/v1/assign", headers=headers).json()["eligible"] is False


def test_unavailable_videos_are_outside_h_and_task(client):
    headers, body = enroll(client)
    flagged = numeric_ids(12)
    unflagged = numeric_ids(12, 8_000_000)
    gone = numeric_ids(26, 9_000_000)
    upload_baseline(client, headers, flagged, unflagged, unavailable=gone)
    result = client.post("/v1/assign", headers=headers).json()
    row = arm_of(client, body)
    assert row["n_rated"] == 24
    assert row["h_baseline"] == pytest.approx(0.5)
    assert not set(result["task_video_ids"]) & set(gone)


def test_unflagged_control_draw_never_uses_flagged(tmp_path):
    client = TestClient(make_app(tmp_path, StudyConfig(control_draw="unflagged")))
    seen_control = False
    for i in range(10):
        headers, body = enroll(client, f"p{i}")
        flagged = numeric_ids(15, 7_000_000 + i * 1000)
        upload_baseline(client, headers, flagged, numeric_ids(35, 8_000_000 + i * 1000))
        result = client.post("/v1/assign", headers=headers).json()
        if arm_of(client, body)["arm"] == "control":
            seen_control = True
            assert not set(result["task_video_ids"]) & set(flagged)
    assert seen_control


def test_no_signal_control_gets_no_task(tmp_path):
    client = TestClient(make_app(tmp_path, StudyConfig(control_arm="no_signal")))
    outcomes = {}
    for i in range(8):
        headers, body = enroll(client, f"p{i}")
        upload_baseline(client, headers, numeric_ids(15, 7_000_000 + i * 1000), numeric_ids(35, 8_000_000 + i * 1000))
        result = client.post("/v1/assign", headers=headers).json()
        outcomes.setdefault(arm_of(client, body)["arm"], set()).add(len(result["task_video_ids"]))
    assert outcomes == {"treatment": {10}, "control": {0}}


def test_arms_balance_within_strata(client):
    rng = random.Random(7)
    counts: dict[int, dict[str, int]] = {}
    for i in range(240):
        headers, body = enroll(client, f"p{i}")
        n_flagged = rng.choice([2, 8, 25])  # Falls in low, middle and high h strata.
        upload_baseline(
            client,
            headers,
            numeric_ids(n_flagged, 7_000_000 + i * 1000),
            numeric_ids(50 - n_flagged, 8_000_000 + i * 1000),
        )
        client.post("/v1/assign", headers=headers)
        row = arm_of(client, body)
        counts.setdefault(row["stratum"], {"treatment": 0, "control": 0})[row["arm"]] += 1
    assert set(counts) == {0, 1, 2}
    for by_arm in counts.values():
        # Permuted blocks of 4 keep the gap within 2 at any point.
        assert abs(by_arm["treatment"] - by_arm["control"]) <= 2


def test_strata_cuts():
    assert assignment.stratum_for(0.05, [0.1, 0.25]) == 0
    assert assignment.stratum_for(0.1, [0.1, 0.25]) == 1
    assert assignment.stratum_for(0.25, [0.1, 0.25]) == 2


# --- Completion


def test_completion_code_is_stable(client):
    headers, _ = enroll(client)
    first = client.post("/v1/complete", headers=headers).json()["completion_code"]
    assert client.post("/v1/complete", headers=headers).json()["completion_code"] == first


# --- First stage


def test_nearest_neighbours_recovers_topic():
    extractor = StubFeatureExtractor()
    rng = random.Random(1)
    rated = [video_id(rng, rng.randrange(4)) for _ in range(60)]
    flags = np.array([topic_of(v) == HARMFUL_TOPIC for v in rated])
    classifier = NearestNeighbours(np.stack([extractor.embed(v) for v in rated]), flags)
    test_ids = [video_id(rng, rng.randrange(4)) for _ in range(200)]
    predicted = classifier.is_harmful(np.stack([extractor.embed(v) for v in test_ids]))
    truth = np.array([topic_of(v) == HARMFUL_TOPIC for v in test_ids])
    assert (predicted == truth).mean() > 0.95


def test_first_stage_recovers_injected_effect(tmp_path):
    """Pipeline check on synthetic data: enrol, rate, assign, upload windows, estimate."""
    client = TestClient(make_app(tmp_path))
    rng = random.Random(11)
    harmful_share = 0.3
    injected = -0.12

    for i in range(200):
        headers, body = enroll(client, f"p{i}")
        rated = [video_id(rng, draw_topic(rng, harmful_share)) for _ in range(50)]
        flagged = [v for v in rated if topic_of(v) == HARMFUL_TOPIC]
        unflagged = [v for v in rated if topic_of(v) != HARMFUL_TOPIC]
        upload_baseline(client, headers, flagged, unflagged)
        client.post("/v1/assign", headers=headers)
        arm = arm_of(client, body)["arm"]
        if arm is None:
            continue

        post_share = harmful_share + (injected if arm == "treatment" else 0.0)
        rows = [
            {"video_id": video_id(rng, draw_topic(rng, harmful_share)), "window": "pre", "watch_count": 1}
            for _ in range(150)
        ] + [
            {"video_id": video_id(rng, draw_topic(rng, post_share)), "window": "post", "watch_count": 1}
            for _ in range(150)
        ]
        response = client.post(
            "/v1/videos",
            headers=headers,
            json={
                "round": "endline",
                "summary": {
                    "watch_count": 300,
                    "like_count": 0,
                    "favorite_count": 0,
                    "dropped_no_video_id": 0,
                    "dropped_bad_date": 0,
                    "ignored_other_records": 0,
                    "window_days": 28,
                },
                "videos": rows,
            },
        )
        assert response.status_code == 204

    result = compute_first_stage(client.app.state.conn, StubFeatureExtractor())
    assert result["n_participants"] >= 190
    assert result["first_stage_effect"] == pytest.approx(injected, abs=0.02)
    assert "Stub" in result["warning"]


# --- Development routes


def test_dev_routes_are_off_by_default(client):
    headers, _ = enroll(client)
    response = client.get(
        "/dev/export", headers=headers, params={"round": "baseline", "now_millis": 1_790_000_000_000}
    )
    assert response.status_code == 404


def test_dev_export_serves_a_zip_when_enabled(tmp_path):
    client = TestClient(make_app(tmp_path, StudyConfig(**emulator_dev_config())))
    headers, _ = enroll(client)
    response = client.get(
        "/dev/export", headers=headers, params={"round": "baseline", "now_millis": 1_790_000_000_000}
    )
    assert response.status_code == 200
    assert response.content[:2] == b"PK"
