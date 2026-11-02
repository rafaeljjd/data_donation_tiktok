package io.github.rafaeljjd.feedstudy.usage

import java.time.Instant
import java.time.ZoneId

enum class UsageEventType { RESUMED, PAUSED, SCREEN_OFF, SHUTDOWN }

data class RawUsageEvent(
    val timeMillis: Long,
    val packageName: String,
    val className: String?,
    val type: UsageEventType,
)

data class Session(val packageName: String, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

data class SessionResult(
    val closed: List<Session>,
    /** Sessions still open at the end of the events: package name to start time. */
    val open: Map<String, Long>,
)

object SessionBuilder {
    /** Activity changes inside one app produce a brief pause, which should not split a session. */
    const val MERGE_GAP_MILLIS = 2_000L

    fun build(events: List<RawUsageEvent>, tracked: Set<String>): SessionResult {
        // Each package's resumed activities, because A can pause after B resumes.
        val resumed = HashMap<String, MutableSet<String?>>()
        val starts = HashMap<String, Long>()
        val closed = ArrayList<Session>()

        fun close(pkg: String, time: Long) {
            val start = starts.remove(pkg) ?: return
            resumed.remove(pkg)
            closed += Session(pkg, start, time)
        }

        for (event in events.sortedBy { it.timeMillis }) {
            when (event.type) {
                UsageEventType.RESUMED -> if (event.packageName in tracked) {
                    resumed.getOrPut(event.packageName) { HashSet() } += event.className
                    starts.putIfAbsent(event.packageName, event.timeMillis)
                }
                UsageEventType.PAUSED -> {
                    val active = resumed[event.packageName] ?: continue
                    active -= event.className
                    if (active.isEmpty()) close(event.packageName, event.timeMillis)
                }
                UsageEventType.SCREEN_OFF, UsageEventType.SHUTDOWN ->
                    starts.keys.toList().forEach { close(it, event.timeMillis) }
            }
        }
        return SessionResult(merge(closed), starts.toMap())
    }

    private fun merge(sessions: List<Session>): List<Session> =
        sessions.groupBy { it.packageName }.values.flatMap { group ->
            val merged = ArrayList<Session>()
            for (session in group.sortedBy { it.startMillis }) {
                val last = merged.lastOrNull()
                if (last != null && session.startMillis - last.endMillis <= MERGE_GAP_MILLIS) {
                    merged[merged.lastIndex] = last.copy(endMillis = session.endMillis)
                } else {
                    merged += session
                }
            }
            merged
        }.sortedBy { it.startMillis }

    /** Foreground time of [packages] inside a window, used as the task compliance proxy. */
    fun foregroundMillis(
        sessions: List<Session>,
        packages: Set<String>,
        startMillis: Long,
        endMillis: Long,
    ): Long = sessions.filter { it.packageName in packages }.sumOf {
        (minOf(it.endMillis, endMillis) - maxOf(it.startMillis, startMillis)).coerceAtLeast(0)
    }
}

/** Splits time into the days that usage is reported by. */
interface DayBucketer {
    fun key(timeMillis: Long): String
    fun nextBoundary(timeMillis: Long): Long
}

class LocalDateBucketer(private val zone: ZoneId) : DayBucketer {
    override fun key(timeMillis: Long): String =
        Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate().toString()

    override fun nextBoundary(timeMillis: Long): Long =
        Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Compressed study days, for testing 28 days in minutes. Keys are labelled as simulated. */
class CompressedBucketer(private val originMillis: Long, private val dayMillis: Long) : DayBucketer {
    private fun index(timeMillis: Long) = Math.floorDiv(timeMillis - originMillis, dayMillis)

    override fun key(timeMillis: Long): String = "sim-day-${index(timeMillis)}"

    override fun nextBoundary(timeMillis: Long): Long =
        originMillis + (index(timeMillis) + 1) * dayMillis
}

data class UsageDay(
    val day: String,
    val packageName: String,
    val foregroundSeconds: Long,
    val sessions: Int,
)

object UsageAggregator {
    /** Splits sessions at day boundaries. A session counts toward the day it started. */
    fun aggregate(sessions: List<Session>, bucketer: DayBucketer): List<UsageDay> {
        val millis = HashMap<Pair<String, String>, Long>()
        val counts = HashMap<Pair<String, String>, Int>()

        for (session in sessions) {
            val startKey = session.packageName to bucketer.key(session.startMillis)
            counts[startKey] = (counts[startKey] ?: 0) + 1
            var cursor = session.startMillis
            while (cursor < session.endMillis) {
                val stop = minOf(bucketer.nextBoundary(cursor), session.endMillis)
                val key = session.packageName to bucketer.key(cursor)
                millis[key] = (millis[key] ?: 0L) + (stop - cursor)
                cursor = stop
            }
            millis.putIfAbsent(startKey, 0L)
        }
        return millis.map { (key, total) ->
            UsageDay(key.second, key.first, (total + 500) / 1000, counts[key] ?: 0)
        }.sortedWith(compareBy({ it.day }, { it.packageName }))
    }
}
