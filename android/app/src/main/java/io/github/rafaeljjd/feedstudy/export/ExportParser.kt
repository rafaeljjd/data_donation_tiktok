package io.github.rafaeljjd.feedstudy.export

import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class RecordKind { WATCH, LIKE, FAVORITE }

data class ExportRecord(
    val kind: RecordKind,
    val videoId: String,
    val url: String,
    val watchedAtMillis: Long,
)

data class ParseStats(
    val watchCount: Int,
    val likeCount: Int,
    val favoriteCount: Int,
    /** Links with no numeric video ID, such as short links. */
    val droppedNoVideoId: Int,
    val droppedBadDate: Int,
    /** Date-and-link records in a section that is not watch, like or favourite history. */
    val ignoredOtherRecords: Int,
    val earliestWatchMillis: Long?,
    val latestWatchMillis: Long?,
)

data class ParsedExport(val records: List<ExportRecord>, val stats: ParseStats)

object VideoIds {
    private val ID = Regex("""/(?:video|v)/(\d{6,25})""")

    fun fromUrl(url: String): String? = ID.find(url)?.groupValues?.get(1)
}

object ExportDates {
    private val SPACED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Timestamps without a zone are read as UTC. Check this against a real export. */
    fun parseMillis(raw: String): Long? {
        val text = raw.trim().removeSuffix("UTC").trim()
        return runCatching {
            LocalDateTime.parse(text, SPACED).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.recoverCatching {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        }.recoverCatching {
            Instant.parse(text).toEpochMilli()
        }.recoverCatching {
            LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
    }
}

object ExportParser {
    fun parse(input: InputStream): ParsedExport {
        val records = ArrayList<ExportRecord>()
        var droppedNoId = 0
        var droppedBadDate = 0
        var ignored = 0

        val walker = ExportWalker(object : ExportWalker.Sink {
            override fun onDatedLink(path: List<String>, date: String, link: String) {
                val kind = ExportPaths.classify(path)
                if (kind == null) {
                    ignored++
                    return
                }
                val id = VideoIds.fromUrl(link)
                val millis = ExportDates.parseMillis(date)
                when {
                    id == null -> droppedNoId++
                    millis == null -> droppedBadDate++
                    else -> records += ExportRecord(kind, id, link, millis)
                }
            }
        })
        ExportInput.forEachJson(input) { walker.walk(it) }

        val watches = records.filter { it.kind == RecordKind.WATCH }
        return ParsedExport(
            records,
            ParseStats(
                watchCount = watches.size,
                likeCount = records.count { it.kind == RecordKind.LIKE },
                favoriteCount = records.count { it.kind == RecordKind.FAVORITE },
                droppedNoVideoId = droppedNoId,
                droppedBadDate = droppedBadDate,
                ignoredOtherRecords = ignored,
                earliestWatchMillis = watches.minOfOrNull { it.watchedAtMillis },
                latestWatchMillis = watches.maxOfOrNull { it.watchedAtMillis },
            ),
        )
    }
}

/**
 * Describes an export's shape without its content, so a real export can be checked by sharing
 * only this text. Reports key paths, counts and date ranges. Never reports values.
 */
object ExportStructureReport {
    private const val MAX_DEPTH = 6
    private val SAFE_KEY = Regex("""^[A-Za-z0-9 _\-]{1,40}$""")
    private val DIGITS = Regex("""\d""")

    private class Section(var containers: Int = 0, var records: Int = 0) {
        var kind: RecordKind? = null
        var withVideoId = 0
        var badDates = 0
        var earliest: Long? = null
        var latest: Long? = null
    }

    fun build(input: InputStream): String {
        val sections = sortedMapOf<String, Section>()
        val skipped = sortedSetOf<String>()

        val walker = ExportWalker(object : ExportWalker.Sink {
            override fun onSkippedSection(path: List<String>) {
                skipped += describe(path)
            }

            override fun onContainer(path: List<String>, isArray: Boolean) {
                if (path.size in 1..MAX_DEPTH) {
                    sections.getOrPut(describe(path)) { Section() }.containers++
                }
            }

            override fun onDatedLink(path: List<String>, date: String, link: String) {
                val section = sections.getOrPut(describe(path)) { Section() }
                section.records++
                section.kind = ExportPaths.classify(path)
                if (VideoIds.fromUrl(link) != null) section.withVideoId++
                val millis = ExportDates.parseMillis(date)
                if (millis == null) {
                    section.badDates++
                } else {
                    section.earliest = minOf(section.earliest ?: millis, millis)
                    section.latest = maxOf(section.latest ?: millis, millis)
                }
            }
        })

        return try {
            ExportInput.forEachJson(input) { walker.walk(it) }
            render(sections, skipped)
        } catch (e: ExportFormatException) {
            "Export structure: could not read file (${e.reason})"
        }
    }

    /** Masks digits and unusual keys, so usernames or IDs in key names cannot leak. */
    private fun describe(path: List<String>): String = path.take(MAX_DEPTH).joinToString(" > ") {
        if (SAFE_KEY.matches(it)) it.replace(DIGITS, "#") else "<key>"
    }

    private fun render(sections: Map<String, Section>, skipped: Set<String>): String = buildString {
        appendLine("Export structure (no content)")
        val withRecords = sections.filterValues { it.records > 0 }
        if (withRecords.isEmpty()) appendLine("No date-and-link records found.")
        for ((path, s) in withRecords) {
            appendLine(
                "- $path: ${s.records} records, ${s.withVideoId} with video ID, " +
                    "${s.badDates} bad dates, kind=${s.kind ?: "ignored"}, " +
                    "range ${day(s.earliest)} to ${day(s.latest)}",
            )
        }
        appendLine("Other sections read:")
        sections.filterValues { it.records == 0 }.keys.forEach { appendLine("- $it") }
        appendLine("Sections skipped without reading:")
        skipped.forEach { appendLine("- $it") }
    }

    private fun day(millis: Long?): String =
        millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
            ?: "n/a"
}
