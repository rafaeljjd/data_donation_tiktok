package io.github.rafaeljjd.feedstudy.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.Instant
import java.time.ZoneId

data class EventsResult(
    val events: List<RawUsageEvent>,
    /** Oldest event of any app, which shows how much history this phone keeps. */
    val earliestAnyEventMillis: Long?,
)

data class DailyStatRow(val day: String, val packageName: String, val foregroundSeconds: Long)

data class DailyStatsResult(val rows: List<DailyStatRow>, val earliestBucketMillis: Long?)

class UsageReader(private val context: Context) {
    private val manager = context.getSystemService(UsageStatsManager::class.java)

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun events(startMillis: Long, endMillis: Long, tracked: Set<String>): EventsResult {
        val out = ArrayList<RawUsageEvent>()
        var earliest: Long? = null
        val query = manager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        while (query.hasNextEvent()) {
            query.getNextEvent(event)
            earliest = minOf(earliest ?: event.timeStamp, event.timeStamp)
            val type = when (event.eventType) {
                ACTIVITY_RESUMED -> UsageEventType.RESUMED
                ACTIVITY_PAUSED -> UsageEventType.PAUSED
                SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
                DEVICE_SHUTDOWN -> UsageEventType.SHUTDOWN
                else -> null
            } ?: continue
            val relevant = type == UsageEventType.SCREEN_OFF ||
                type == UsageEventType.SHUTDOWN ||
                event.packageName in tracked
            if (relevant) {
                out += RawUsageEvent(event.timeStamp, event.packageName, event.className, type)
            }
        }
        return EventsResult(out, earliest)
    }

    /**
     * Android's own daily totals. Kept beside the event-based figures because bucket boundaries
     * need not match local midnight. A day can have several buckets, so this keeps the largest.
     */
    fun dailyStats(startMillis: Long, endMillis: Long, tracked: Set<String>): DailyStatsResult {
        val zone = ZoneId.systemDefault()
        val best = HashMap<Pair<String, String>, Long>()
        var earliest: Long? = null
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMillis, endMillis)
        for (stat in stats.orEmpty()) {
            earliest = minOf(earliest ?: stat.firstTimeStamp, stat.firstTimeStamp)
            if (stat.packageName !in tracked) continue
            val day = Instant.ofEpochMilli(stat.firstTimeStamp).atZone(zone).toLocalDate().toString()
            val key = day to stat.packageName
            best[key] = maxOf(best[key] ?: 0L, stat.totalTimeInForeground / 1000)
        }
        val rows = best.map { (key, seconds) -> DailyStatRow(key.first, key.second, seconds) }
        return DailyStatsResult(rows.sortedBy { it.day }, earliest)
    }

    private companion object {
        // Plain values, because some of the named constants need newer API levels than minSdk.
        const val ACTIVITY_RESUMED = 1
        const val ACTIVITY_PAUSED = 2
        const val SCREEN_NON_INTERACTIVE = 16
        const val DEVICE_SHUTDOWN = 26
    }
}
