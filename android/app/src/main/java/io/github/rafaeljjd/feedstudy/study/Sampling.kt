package io.github.rafaeljjd.feedstudy.study

import io.github.rafaeljjd.feedstudy.export.ExportRecord
import io.github.rafaeljjd.feedstudy.export.RecordKind
import kotlin.random.Random

/** One sampled video, with its link kept on the phone for replay and deep linking. */
data class SampledVideo(val videoId: String, val url: String)

data class WindowCount(val videoId: String, val window: String, val watchCount: Int)

data class EndlineWindows(
    val pre: List<WindowCount>,
    val post: List<WindowCount>,
    /** Videos in the post window that can be sampled for endline rating. */
    val postPool: List<SampledVideo>,
    val windowMillis: Long,
)

object Windows {
    const val BASELINE_POOL = "baseline_pool"
    const val PRE = "pre"
    const val POST = "post"

    private fun watches(records: List<ExportRecord>) =
        records.filter { it.kind == RecordKind.WATCH }

    /** Watches in the lookback period that ends at the export's last record. */
    fun baselinePool(records: List<ExportRecord>, lookbackMillis: Long): List<ExportRecord> {
        val watched = watches(records)
        val end = watched.maxOfOrNull { it.watchedAtMillis } ?: return emptyList()
        return watched.filter { it.watchedAtMillis in (end - lookbackMillis)..end }
    }

    fun counts(records: List<ExportRecord>, window: String): List<WindowCount> =
        records.groupingBy { it.videoId }.eachCount()
            .map { (id, n) -> WindowCount(id, window, n) }
            .sortedBy { it.videoId }

    /**
     * Post window: from treatment to the second export's last record, without the task videos
     * themselves, because opening them may add them to watch history in a way that differs by
     * arm. Pre window: the same length, ending at baseline.
     */
    fun endline(
        records: List<ExportRecord>,
        baselineAtMillis: Long,
        treatedAtMillis: Long,
        taskVideoIds: Set<String>,
    ): EndlineWindows {
        // The two exports overlap, so the same viewing can appear in both.
        val watched = watches(records).distinctBy { it.videoId to it.watchedAtMillis }
        val end = watched.maxOfOrNull { it.watchedAtMillis } ?: treatedAtMillis
        val length = (end - treatedAtMillis).coerceAtLeast(0)

        val post = watched.filter {
            it.watchedAtMillis in treatedAtMillis..end && it.videoId !in taskVideoIds
        }
        val pre = watched.filter {
            it.watchedAtMillis >= baselineAtMillis - length && it.watchedAtMillis < baselineAtMillis
        }
        return EndlineWindows(
            pre = counts(pre, PRE),
            post = counts(post, POST),
            postPool = post.distinctBy { it.videoId }.map { SampledVideo(it.videoId, it.url) },
            windowMillis = length,
        )
    }
}

object RatingSampler {
    /** Extra videos held in reserve, used when a sampled video can no longer be played. */
    private const val RESERVE_MULTIPLIER = 2

    /** Returns distinct videos in random order. Rating stops at [sampleSize] answers. */
    fun draw(pool: List<SampledVideo>, sampleSize: Int, seed: Long): List<SampledVideo> =
        pool.distinctBy { it.videoId }
            .sortedBy { it.videoId }
            .shuffled(Random(seed))
            .take(sampleSize * RESERVE_MULTIPLIER)
}
