package io.github.rafaeljjd.feedstudy

import io.github.rafaeljjd.feedstudy.export.ExportRecord
import io.github.rafaeljjd.feedstudy.export.RecordKind
import io.github.rafaeljjd.feedstudy.rating.PlayerEvent
import io.github.rafaeljjd.feedstudy.rating.PlayerEvents
import io.github.rafaeljjd.feedstudy.study.Phase
import io.github.rafaeljjd.feedstudy.study.RatingSampler
import io.github.rafaeljjd.feedstudy.study.SampledVideo
import io.github.rafaeljjd.feedstudy.study.StudyClock
import io.github.rafaeljjd.feedstudy.study.StudyEvent
import io.github.rafaeljjd.feedstudy.study.StudyMachine
import io.github.rafaeljjd.feedstudy.study.Windows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyLogicTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun watch(id: String, at: Long, kind: RecordKind = RecordKind.WATCH) =
        ExportRecord(kind, id, "https://www.tiktok.com/@a/video/$id", at)

    // --- Windows

    @Test fun baselinePoolKeepsOnlyWatchesInLookback() {
        val end = 100 * day
        val pool = Windows.baselinePool(
            listOf(
                watch("old", end - 30 * day),
                watch("recent", end - 5 * day),
                watch("latest", end),
                watch("liked", end, RecordKind.LIKE),
            ),
            lookbackMillis = 28 * day,
        )
        assertEquals(setOf("recent", "latest"), pool.map { it.videoId }.toSet())
    }

    @Test fun endlineWindowsHaveEqualLengthAndExcludeTaskVideos() {
        val baseline = 100 * day
        val treated = 103 * day
        val exportEnd = 131 * day // A 28-day post window.
        val windows = Windows.endline(
            listOf(
                watch("before-pre", baseline - 29 * day),
                watch("in-pre", baseline - 28 * day),
                watch("in-pre-2", baseline - 1),
                watch("between", baseline + 1 * day),
                watch("before-treatment", treated - 1),
                watch("task", treated + hour),
                watch("in-post", treated + hour),
                watch("in-post", treated + 2 * hour),
                watch("last", exportEnd),
            ),
            baselineAtMillis = baseline,
            treatedAtMillis = treated,
            taskVideoIds = setOf("task"),
        )
        assertEquals(28 * day, windows.windowMillis)
        assertEquals(setOf("in-pre", "in-pre-2"), windows.pre.map { it.videoId }.toSet())
        assertEquals(setOf("in-post", "last"), windows.post.map { it.videoId }.toSet())
        assertEquals(2, windows.post.single { it.videoId == "in-post" }.watchCount)
        assertFalse(windows.postPool.any { it.videoId == "task" })
    }

    @Test fun taskVideoSeenBeforeTreatmentStaysInPreWindow() {
        val baseline = 100 * day
        val windows = Windows.endline(
            listOf(watch("task", baseline - hour), watch("end", 110 * day)),
            baselineAtMillis = baseline,
            treatedAtMillis = 101 * day,
            taskVideoIds = setOf("task"),
        )
        assertEquals(listOf("task"), windows.pre.map { it.videoId })
    }

    @Test fun overlappingExportsDoNotDoubleCount() {
        val records = listOf(watch("a", 105 * day), watch("a", 105 * day), watch("end", 110 * day))
        val windows = Windows.endline(records, 100 * day, 101 * day, emptySet())
        assertEquals(1, windows.post.single { it.videoId == "a" }.watchCount)
    }

    @Test fun exportEndingBeforeTreatmentGivesEmptyWindows() {
        val windows = Windows.endline(listOf(watch("a", 99 * day)), 100 * day, 101 * day, emptySet())
        assertEquals(0L, windows.windowMillis)
        assertTrue(windows.post.isEmpty())
        assertTrue(windows.pre.isEmpty())
    }

    // --- Sampling

    @Test fun samplerDeduplicatesKeepsReserveAndIsReproducible() {
        val pool = (1..200).flatMap { i ->
            List(2) { SampledVideo("id$i", "https://x/video/$i") }
        }
        val first = RatingSampler.draw(pool, sampleSize = 50, seed = 7)
        val again = RatingSampler.draw(pool.shuffled(), sampleSize = 50, seed = 7)
        assertEquals(100, first.size)
        assertEquals(100, first.map { it.videoId }.toSet().size)
        assertEquals(first, again)
        assertTrue(first != RatingSampler.draw(pool, 50, seed = 8))
    }

    @Test fun samplerHandlesSmallPools() {
        val pool = listOf(SampledVideo("a", "u"))
        assertEquals(1, RatingSampler.draw(pool, 50, 1).size)
        assertTrue(RatingSampler.draw(emptyList(), 50, 1).isEmpty())
    }

    // --- Phase machine

    @Test fun walksTheWholeStudyInOrder() {
        val steps = listOf(
            StudyEvent.ENROLLED to Phase.CONSENT,
            StudyEvent.CONSENTED to Phase.USAGE_ACCESS,
            StudyEvent.USAGE_GRANTED to Phase.EXPORT_GUIDE_1,
            StudyEvent.EXPORT_REQUESTED to Phase.EXPORT_IMPORT_1,
            StudyEvent.EXPORT_IMPORTED to Phase.RATING_1,
            StudyEvent.RATING_DONE to Phase.VALUATION_1,
            StudyEvent.VALUATION_DONE to Phase.ASSIGNING,
            StudyEvent.ASSIGNED_WITH_TASK to Phase.TASK,
            StudyEvent.TASK_DONE to Phase.TRACKING,
            StudyEvent.TRACKING_ELAPSED to Phase.EXPORT_GUIDE_2,
            StudyEvent.EXPORT_REQUESTED to Phase.EXPORT_IMPORT_2,
            StudyEvent.EXPORT_IMPORTED to Phase.RATING_2,
            StudyEvent.RATING_DONE to Phase.VALUATION_2,
            StudyEvent.VALUATION_DONE to Phase.FINISHING,
            StudyEvent.COMPLETED to Phase.DONE,
        )
        var phase = Phase.ENROLL
        for ((event, expected) in steps) {
            phase = checkNotNull(StudyMachine.next(phase, event)) { "$event from $phase" }
            assertEquals(expected, phase)
        }
    }

    @Test fun baselineValuationComesBeforeRandomization() {
        assertEquals(Phase.VALUATION_1, StudyMachine.next(Phase.RATING_1, StudyEvent.RATING_DONE))
        assertNull(StudyMachine.next(Phase.RATING_1, StudyEvent.ASSIGNED_WITH_TASK))
    }

    @Test fun rejectsEventsThatDoNotApply() {
        assertNull(StudyMachine.next(Phase.TRACKING, StudyEvent.ENROLLED))
        assertNull(StudyMachine.next(Phase.DONE, StudyEvent.COMPLETED))
    }

    @Test fun noSignalAndIneligibleBranches() {
        assertEquals(Phase.TRACKING, StudyMachine.next(Phase.ASSIGNING, StudyEvent.ASSIGNED_NO_TASK))
        assertEquals(Phase.INELIGIBLE, StudyMachine.next(Phase.ASSIGNING, StudyEvent.FOUND_INELIGIBLE))
    }

    // --- Clock

    @Test fun compressedClockEndsTracking() {
        assertFalse(StudyClock.isTrackingElapsed(55_000, 0, 28, 2))
        assertTrue(StudyClock.isTrackingElapsed(56_000, 0, 28, 2))
        assertEquals(27L, StudyClock.dayNumber(55_000, 0, 2))
    }

    // --- Player messages

    @Test fun parsesPlayerMessages() {
        assertEquals(
            PlayerEvent.Ready,
            PlayerEvents.parse("""{"x-tiktok-player":true,"type":"onPlayerReady"}"""),
        )
        assertEquals(
            PlayerEvent.StateChange(1),
            PlayerEvents.parse("""{"x-tiktok-player":true,"type":"onStateChange","value":1}"""),
        )
        assertEquals(
            PlayerEvent.Time(3.5, 20.0),
            PlayerEvents.parse(
                """{"x-tiktok-player":true,"type":"onCurrentTime","value":{"currentTime":3.5,"duration":20}}""",
            ),
        )
        assertEquals(
            PlayerEvent.Error(1001),
            PlayerEvents.parse(
                """{"x-tiktok-player":true,"type":"onPlayerError","value":{"errorCode":1001,"errorType":"INVALID_VIDEO"}}""",
            ),
        )
    }

    @Test fun readsTheBannedCodeAndIgnoresDeprecatedOnError() {
        // Both messages were captured from TikTok's real player, in this order.
        assertNull(PlayerEvents.parse("""{"type":"onError","value":1,"x-tiktok-player":true}"""))
        assertEquals(
            PlayerEvent.Error(1002),
            PlayerEvents.parse(
                """{"type":"onPlayerError","value":{"errorCode":1002,"errorType":"VIDEO_EMBED_BANNED"},"x-tiktok-player":true}""",
            ),
        )
        assertNull(PlayerEvents.parse("[tea-sdk]ready"))
        assertNull(PlayerEvents.parse("""{"signalSource":"","width":372,"height":45}"""))
    }

    @Test fun ignoresOtherMessages() {
        assertNull(PlayerEvents.parse("""{"type":"onPlayerReady"}"""))
        assertNull(PlayerEvents.parse("not json"))
        assertNull(PlayerEvents.parse("""{"x-tiktok-player":true,"type":"somethingNew"}"""))
    }
}
