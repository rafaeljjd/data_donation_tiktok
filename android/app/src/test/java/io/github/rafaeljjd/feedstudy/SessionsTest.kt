package io.github.rafaeljjd.feedstudy

import io.github.rafaeljjd.feedstudy.usage.CompressedBucketer
import io.github.rafaeljjd.feedstudy.usage.LocalDateBucketer
import io.github.rafaeljjd.feedstudy.usage.RawUsageEvent
import io.github.rafaeljjd.feedstudy.usage.Session
import io.github.rafaeljjd.feedstudy.usage.SessionBuilder
import io.github.rafaeljjd.feedstudy.usage.UsageAggregator
import io.github.rafaeljjd.feedstudy.usage.UsageEventType.PAUSED
import io.github.rafaeljjd.feedstudy.usage.UsageEventType.RESUMED
import io.github.rafaeljjd.feedstudy.usage.UsageEventType.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class SessionsTest {
    private val tiktok = "com.zhiliaoapp.musically"
    private val instagram = "com.instagram.android"
    private val tracked = setOf(tiktok, instagram)

    private fun event(t: Long, pkg: String, type: io.github.rafaeljjd.feedstudy.usage.UsageEventType, cls: String? = "Main") =
        RawUsageEvent(t, pkg, cls, type)

    @Test fun buildsOneSessionFromResumeAndPause() {
        val result = SessionBuilder.build(
            listOf(event(1_000, tiktok, RESUMED), event(61_000, tiktok, PAUSED)),
            tracked,
        )
        assertEquals(listOf(Session(tiktok, 1_000, 61_000)), result.closed)
        assertTrue(result.open.isEmpty())
    }

    @Test fun ignoresUntrackedApps() {
        val result = SessionBuilder.build(
            listOf(event(1_000, "com.other", RESUMED), event(2_000, "com.other", PAUSED)),
            tracked,
        )
        assertTrue(result.closed.isEmpty())
    }

    @Test fun activityChangeInsideAppIsOneSession() {
        val result = SessionBuilder.build(
            listOf(
                event(1_000, tiktok, RESUMED, "A"),
                event(10_000, tiktok, PAUSED, "A"),
                event(10_500, tiktok, RESUMED, "B"),
                event(20_000, tiktok, PAUSED, "B"),
            ),
            tracked,
        )
        assertEquals(listOf(Session(tiktok, 1_000, 20_000)), result.closed)
    }

    @Test fun lateActivityPauseDoesNotEndTheSession() {
        val result = SessionBuilder.build(
            listOf(
                event(1_000, tiktok, RESUMED, "A"),
                event(10_000, tiktok, RESUMED, "B"),
                event(10_100, tiktok, PAUSED, "A"),
                event(20_000, tiktok, PAUSED, "B"),
            ),
            tracked,
        )
        assertEquals(listOf(Session(tiktok, 1_000, 20_000)), result.closed)
    }

    @Test fun longerGapStartsNewSession() {
        val result = SessionBuilder.build(
            listOf(
                event(1_000, tiktok, RESUMED),
                event(2_000, tiktok, PAUSED),
                event(60_000, tiktok, RESUMED),
                event(61_000, tiktok, PAUSED),
            ),
            tracked,
        )
        assertEquals(2, result.closed.size)
    }

    @Test fun screenOffClosesOpenSessions() {
        val result = SessionBuilder.build(
            listOf(event(1_000, tiktok, RESUMED), event(5_000, "android", SCREEN_OFF, null)),
            tracked,
        )
        assertEquals(listOf(Session(tiktok, 1_000, 5_000)), result.closed)
    }

    @Test fun reportsSessionStillOpen() {
        val result = SessionBuilder.build(listOf(event(1_000, tiktok, RESUMED)), tracked)
        assertTrue(result.closed.isEmpty())
        assertEquals(mapOf(tiktok to 1_000L), result.open)
    }

    @Test fun pauseWithoutResumeIsIgnored() {
        val result = SessionBuilder.build(listOf(event(1_000, tiktok, PAUSED)), tracked)
        assertTrue(result.closed.isEmpty())
    }

    @Test fun eventsAreSortedBeforeUse() {
        val result = SessionBuilder.build(
            listOf(event(61_000, tiktok, PAUSED), event(1_000, tiktok, RESUMED)),
            tracked,
        )
        assertEquals(1, result.closed.size)
    }

    @Test fun foregroundTimeIsClippedToTheWindow() {
        val sessions = listOf(
            Session(tiktok, 0, 10_000),
            Session(instagram, 10_000, 20_000),
            Session(tiktok, 30_000, 40_000),
        )
        assertEquals(10_000L, SessionBuilder.foregroundMillis(sessions, setOf(tiktok), 5_000, 35_000))
        assertEquals(0L, SessionBuilder.foregroundMillis(sessions, setOf(tiktok), 15_000, 25_000))
    }

    @Test fun splitsSessionsAtLocalMidnight() {
        val zone = ZoneId.of("Europe/Rome")
        val midnight = java.time.LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = UsageAggregator.aggregate(
            listOf(Session(tiktok, midnight - 60_000, midnight + 120_000)),
            LocalDateBucketer(zone),
        )
        assertEquals(2, days.size)
        assertEquals("2026-11-01", days[0].day)
        assertEquals(60L, days[0].foregroundSeconds)
        assertEquals(1, days[0].sessions)
        assertEquals("2026-11-02", days[1].day)
        assertEquals(120L, days[1].foregroundSeconds)
        assertEquals(0, days[1].sessions)
    }

    @Test fun handlesDaylightSavingDay() {
        val zone = ZoneId.of("Europe/Rome") // 25 October 2026 has 25 hours.
        val start = java.time.LocalDate.of(2026, 10, 25).atStartOfDay(zone).toInstant().toEpochMilli()
        val bucketer = LocalDateBucketer(zone)
        assertEquals(25 * 3_600_000L, bucketer.nextBoundary(start) - start)
    }

    @Test fun addsSessionsOnTheSameDayPerApp() {
        val days = UsageAggregator.aggregate(
            listOf(
                Session(tiktok, 0, 30_000),
                Session(tiktok, 60_000, 90_000),
                Session(instagram, 100_000, 110_000),
            ),
            LocalDateBucketer(ZoneOffset.UTC),
        )
        assertEquals(60L, days.single { it.packageName == tiktok }.foregroundSeconds)
        assertEquals(2, days.single { it.packageName == tiktok }.sessions)
        assertEquals(10L, days.single { it.packageName == instagram }.foregroundSeconds)
    }

    @Test fun compressedDaysAreLabelledAsSimulated() {
        val bucketer = CompressedBucketer(originMillis = 1_000_000, dayMillis = 2_000)
        assertEquals("sim-day-0", bucketer.key(1_000_000))
        assertEquals("sim-day-1", bucketer.key(1_002_000))
        assertEquals("sim-day--1", bucketer.key(999_999))
        val days = UsageAggregator.aggregate(listOf(Session(tiktok, 1_001_000, 1_005_000)), bucketer)
        assertEquals(listOf(1L, 2L, 1L), days.map { it.foregroundSeconds })
    }
}
