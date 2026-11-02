# Feed Study: TikTok export experiment prototype

A working first version of the study app: one participant from baseline to endline, on synthetic
data with a compressed clock. **It is not ready for participants.** Consent text, price list, K,
sample size and video features are placeholders.

```
android/   Android app (Kotlin, Compose, Room, WorkManager)
server/    Local API (FastAPI, SQLite): assignment, stub features, first-stage script
fixtures/  Synthetic TikTok export generator
scripts/   Dev server, end-to-end run, database checks
```

## Setup

Needs JDK 17 or later, the Android SDK with platform 37 and build-tools 36, and Python 3.12.

```bash
export JAVA_HOME=~/.local/jdks/jdk-17.0.21+9/Contents/Home   # or any JDK 17+
python3 -m venv .venv && .venv/bin/pip install -r server/requirements.txt
```

**iCloud:** this repo sits in `~/Documents`, which iCloud syncs. Its conflict copies
(`MainActivity 2.class`) broke the build, so Gradle now builds into `~/.cache/feedstudy-build`. On
this Mac, `.venv` and `android/.gradle` are symlinks into `~/.cache/feedstudy`. `.git` is still
synced, which can corrupt a repository, so the lasting fix is to move the repo out of `~/Documents`.

## Tests

```bash
.venv/bin/python -m pytest server/tests              # server
(cd android && ./gradlew :app:testDebugUnitTest)     # parser, sampling, sessions, phases
scripts/e2e.sh                                       # full study on a booted emulator
```

`scripts/e2e.sh` drives the real app through every phase, then `scripts/check_e2e.py` checks the
server database, including that no export content reached it. Chrome stands in for TikTok, because
the emulator cannot run it. This does not test TikTok itself: the phone test below does that.

On a heavily loaded machine, Android may kill slow apps. Disabling background Google services on
the test emulator helped:
`adb shell pm disable-user --user 0 com.google.android.gms` (emulator only).

## Phone test

These questions need a real phone with your TikTok account. Part 1 needs no server.

1. On the phone, turn on Developer options and USB debugging, then connect it.
2. Install the app:
   ```bash
   (cd android && ./gradlew :app:installDebug)
   ```
   Or copy `~/.cache/feedstudy-build/app/outputs/apk/debug/app-debug.apk` to the phone.
3. Open Feed Study and tap **Diagnostics**. Paste the link of a public video from your feed.
4. **Deep link:** tap each **Open** button. For each, answer in the app: did it open the exact
   video, and could you reach **Not interested**?
5. **Embedded player:** tap **Start player**, and say whether the video played on screen.
6. **Usage:** grant usage access, tap **Read usage**, and compare today's minutes with Digital
   Wellbeing. Note the oldest dates, which show how much pre-study history the phone keeps.
   If Android greys out usage access for a sideloaded app, try App info > menu > Allow restricted
   settings (not verified on a device).
7. **Export:** request a JSON export in TikTok. When it arrives, tap **Select export file**. The
   report holds key names, counts and date ranges only. Read it before sharing.
8. Tap **Share report** and send the text to the study team.

### What the answers change

| Result | Change |
|---|---|
| A URL form opens the exact video | Set `deep_link_template` in `server/app/config.py` |
| No form reaches Not interested | The treatment as designed does not work: rethink the task |
| Export report shows other key names | Update `ExportPaths` in `export/ExportWalker.kt` |

### Full flow on a phone (synthetic videos, 90-second tracking)

```bash
.venv/bin/python scripts/run_dev_server.py &
adb reverse tcp:8000 tcp:8000
```

Then open the app and enter any ID. Tasks open your real TikTok on synthetic IDs that do not
exist, so this checks the flow, not the videos.

## Settings

Open design decisions are in `server/app/config.py`, and can also be set through the
`FEEDSTUDY_CONFIG` environment variable as JSON.

| Setting | Default | Note |
|---|---|---|
| `control_arm` | `random_signal` | or `no_signal`; a placebo task is not built |
| `control_draw` | `all_watched` | or `unflagged` |
| `k` | 10 | placeholder; to use all flagged videos, set it very high |
| `rating_sample_size` | 50 | placeholder |
| `hide_creator` | off | adds the instruction in both arms |
| `price_list`, `deactivation_days` | placeholder | recorded only, no payout |
| `h_strata_cuts` | 0.1, 0.25 | placeholder, server only |

Flagged videos are always a random subset. "Most recent" is not built, because the server does not
receive watch dates.

## What leaves the phone

Ratings, survey answers, daily time per app, video IDs with a window label and per-window watch
count, export summary counts, and task timing. The server rejects any other field. Links, titles,
watch timestamps, profile, messages and searches stay on the phone or are never read. TikTok itself
receives the device's IP address and video IDs when a video plays in the embedded player.

## Not built

Real feature extraction (the stub's numbers say nothing about real videos), BDM or payment
settlement, WTA deactivation enforcement, Prolific integration beyond ID entry, production hosting
and authentication, IRB-approved consent text, Play distribution.
