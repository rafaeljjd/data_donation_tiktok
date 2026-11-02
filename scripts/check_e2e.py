#!/usr/bin/env python3
"""Checks that an end-to-end run left a complete, privacy-clean record in the server database.

    python scripts/check_e2e.py path/to/feedstudy.sqlite3
"""

from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from server.app.features import StubFeatureExtractor  # noqa: E402
from server.app.first_stage import compute_first_stage  # noqa: E402

CHROME = "com.android.chrome"
# Text that exists in the synthetic export and must never reach the server.
FORBIDDEN = [
    "SYNTHETIC-PRIVATE-MESSAGE",
    "SYNTHETIC-USERNAME",
    "SYNTHETIC-SEARCH",
    "synthetic@example.com",
    "synthetic_friend",
    "tiktokv.com",
    "http",
]

failures: list[str] = []


def check(name: str, passed: bool, detail: object = "") -> None:
    print(f"{'PASS' if passed else 'FAIL'}  {name}" + (f"  ({detail})" if detail != "" else ""))
    if not passed:
        failures.append(name)


def main(path: str) -> int:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row

    def rows(sql: str, *args: object) -> list[sqlite3.Row]:
        return conn.execute(sql, args).fetchall()

    people = rows("SELECT * FROM participants")
    check("one participant", len(people) == 1, len(people))
    p = people[0]
    pid = p["participant_id"]
    print(
        f"      arm={p['arm']} stratum={p['stratum']} h={p['h_baseline']} "
        f"k_i={p['k_i']} earliest_event={p['earliest_event_day']} "
        f"earliest_daily={p['earliest_daily_stats_day']}"
    )

    check("consent recorded", p["consented_at_millis"] is not None)
    check("assigned an arm", p["arm"] in ("treatment", "control"), p["arm"])
    check("completion code issued", bool(p["completion_code"]), p["completion_code"])
    check("filter WTP recorded", p["filter_wtp"] == 5, p["filter_wtp"])
    check("usage access reported", p["usage_access_granted"] == 1)
    check("usage history range reported", p["earliest_event_day"] is not None)

    for round_name in ("baseline", "endline"):
        rated = rows(
            "SELECT * FROM ratings WHERE round = ? AND status = 'rated'", round_name
        )
        check(f"{round_name}: 12 videos rated", len(rated) == 12, len(rated))
        valuations = rows("SELECT * FROM valuations WHERE round = ?", round_name)
        check(f"{round_name}: 3 WTAs", len(valuations) == 3, len(valuations))
        check(
            f"{round_name}: WTA answers stored",
            all(v["min_accept_price"] == 10 for v in valuations),
        )

    baseline = rows("SELECT * FROM ratings WHERE round = 'baseline' AND status = 'rated'")
    flagged = {r["video_id"] for r in baseline if r["wants_to_stop"]}
    # In the synthetic data, the participant flags topic 0.
    check(
        "ratings follow the synthetic topics",
        flagged == {r["video_id"] for r in baseline if int(r["video_id"]) % 4 == 0},
    )
    check("h matches the ratings", abs(p["h_baseline"] - len(flagged) / 12) < 1e-9, p["h_baseline"])

    task_ids = json.loads(p["task_video_ids"])
    check("task size equals k_i", len(task_ids) == p["k_i"] and p["k_i"] == min(3, len(flagged)), task_ids)
    if p["arm"] == "treatment":
        check("treatment task uses flagged videos", set(task_ids) <= flagged)

    log = rows("SELECT * FROM task_log")
    check("task log covers each video", {r["video_id"] for r in log} == set(task_ids))
    check("every handoff logged", all(r["handoff_at_millis"] for r in log))
    check("every return logged", all(r["returned_at_millis"] for r in log))
    check("return follows handoff", all(r["returned_at_millis"] > r["handoff_at_millis"] for r in log))
    check("handoff went to the stand-in", all(r["handoff_package"] == CHROME for r in log))
    check("self-reports recorded", all(r["self_report"] == "sent" for r in log))
    seconds = [r["foreground_seconds"] for r in log]
    check("compliance proxy measured foreground time", all(s and s > 0 for s in seconds), seconds)

    windows = {
        w: {r["video_id"] for r in rows("SELECT video_id FROM videos WHERE window = ?", w)}
        for w in ("baseline_pool", "pre", "post")
    }
    for name, ids in windows.items():
        check(f"videos in window {name}", len(ids) > 0, len(ids))
    check("task videos excluded from post window", not windows["post"] & set(task_ids))

    summaries = rows("SELECT * FROM export_summaries")
    check("export summaries for both rounds", len(summaries) == 2)
    # The fixture's "Post" section holds this ID. The parser must skip that section unread.
    posted = rows("SELECT video_id FROM videos WHERE video_id = '7399999999999999999'")
    check("own posted videos not counted as watches", not posted, len(posted))

    usage = rows("SELECT * FROM usage_days WHERE package = ?", CHROME)
    from_events = [u for u in usage if u["source"] == "events"]
    check("usage rows from events", len(from_events) > 0, len(from_events))
    check("usage rows from daily totals", any(u["source"] == "daily_stats" for u in usage))
    check("logged stand-in time", sum(u["foreground_seconds"] for u in from_events) > 0,
          sum(u["foreground_seconds"] for u in from_events))
    check("compressed days are labelled", all(u["day"].startswith("sim-day-") for u in from_events))

    dump = "\n".join(conn.iterdump())
    for text in FORBIDDEN:
        check(f"database has no {text!r}", text not in dump)

    result = compute_first_stage(conn, StubFeatureExtractor())
    check("first-stage script runs", result["n_participants"] == 1, result["participants"])
    print(f"      {result['warning']}")

    print(f"\n{len(failures)} failure(s)" if failures else "\nAll checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
