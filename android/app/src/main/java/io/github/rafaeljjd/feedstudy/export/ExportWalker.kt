package io.github.rafaeljjd.feedstudy.export

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class ExportFormatException(val reason: Reason, message: String? = null) :
    Exception(message ?: reason.name) {
    enum class Reason { TXT_FORMAT, NOT_AN_EXPORT, MALFORMED, TOO_LARGE }
}

/** Key-name rules. TikTok does not document the JSON nesting, so rules work on key text. */
internal object ExportPaths {
    private val NON_LETTERS = Regex("[^a-z]")

    fun normalize(key: String): String = key.lowercase().replace(NON_LETTERS, "")

    /** Sections the app never descends into, so their contents are not read at all. */
    private val DENIED = listOf(
        "directmessage", "chat", "message", "profile", "comment", "setting", "income",
        "shop", "golive", "tiktoklive", "livehistory", "post", "search", "following",
        "follower", "blocklist", "hashtag", "sound", "effect", "advert", "ads", "payment",
        "purchase", "location", "login", "device",
    )

    fun isDenied(normalizedKey: String): Boolean = DENIED.any { normalizedKey.contains(it) }

    /**
     * Classifies a record from the deepest key upward. "VideoList" alone is deliberately not
     * enough: TikTok reuses that name for the participant's own posted videos.
     */
    fun classify(path: List<String>): RecordKind? {
        for (segment in path.asReversed().map(::normalize)) {
            when {
                segment.contains("favoritevideo") || segment.contains("favouritevideo") ->
                    return RecordKind.FAVORITE
                segment.contains("itemfavorite") || segment.contains("likelist") ||
                    segment.contains("videolike") || segment.contains("likedvideo") ->
                    return RecordKind.LIKE
                segment.contains("watchhistory") || segment.contains("browsinghistory") ||
                    segment.contains("videobrowsing") ->
                    return RecordKind.WATCH
            }
        }
        return null
    }

    val DATE_KEYS = setOf("date", "datetime", "timestamp")
    val LINK_KEYS = setOf("link", "videolink", "url", "videourl")
}

/**
 * Streaming walk over one JSON document. Primitive values are skipped unless they are the date
 * or link of a record, so titles and other free text are never held in memory.
 */
internal class ExportWalker(private val sink: Sink) {
    interface Sink {
        fun onSkippedSection(path: List<String>) {}
        fun onContainer(path: List<String>, isArray: Boolean) {}
        fun onDatedLink(path: List<String>, date: String, link: String)
    }

    fun walk(input: InputStream) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        try {
            value(reader, emptyList())
        } catch (e: IllegalStateException) {
            throw ExportFormatException(ExportFormatException.Reason.MALFORMED, e.message)
        } catch (e: IOException) {
            throw ExportFormatException(ExportFormatException.Reason.MALFORMED, e.message)
        }
    }

    private fun value(reader: JsonReader, path: List<String>) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> obj(reader, path)
            JsonToken.BEGIN_ARRAY -> {
                sink.onContainer(path, true)
                reader.beginArray()
                while (reader.hasNext()) value(reader, path)
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }

    private fun obj(reader: JsonReader, path: List<String>) {
        sink.onContainer(path, false)
        reader.beginObject()
        var date: String? = null
        var link: String? = null
        while (reader.hasNext()) {
            val key = reader.nextName()
            val normalized = ExportPaths.normalize(key)
            val token = reader.peek()
            val isContainer = token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY
            when {
                isContainer && ExportPaths.isDenied(normalized) -> {
                    sink.onSkippedSection(path + key)
                    reader.skipValue()
                }
                token == JsonToken.STRING && normalized in ExportPaths.DATE_KEYS ->
                    date = reader.nextString()
                token == JsonToken.STRING && normalized in ExportPaths.LINK_KEYS ->
                    link = reader.nextString()
                else -> value(reader, path + key)
            }
        }
        reader.endObject()
        if (date != null && link != null) sink.onDatedLink(path, date, link)
    }
}

/** Opens a zip or plain JSON export as a stream, without writing anything to disk. */
internal object ExportInput {
    private const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024
    private const val HEAD_BYTES = 4096

    fun forEachJson(raw: InputStream, action: (InputStream) -> Unit) {
        val input = BufferedInputStream(raw, 64 * 1024)
        input.mark(HEAD_BYTES)
        val head = ByteArray(HEAD_BYTES)
        val read = readUpTo(input, head)
        input.reset()

        when {
            read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                readZip(input, action)
            looksLikeJson(head, read) -> action(NonClosing(input))
            else -> throw ExportFormatException(ExportFormatException.Reason.TXT_FORMAT)
        }
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun looksLikeJson(head: ByteArray, length: Int): Boolean {
        var i = 0
        // A UTF-8 byte order mark may precede the document.
        if (length >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() &&
            head[2] == 0xBF.toByte()
        ) i = 3
        while (i < length) {
            val c = head[i].toInt().toChar()
            if (c == '{' || c == '[') return true
            if (!c.isWhitespace()) return false
            i++
        }
        return false
    }

    private fun readZip(input: InputStream, action: (InputStream) -> Unit) {
        var sawJson = false
        var sawTxt = false
        val budget = Budget(MAX_UNCOMPRESSED_BYTES)
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.lowercase()
                val baseName = name.substringAfterLast('/')
                if (entry.isDirectory || name.startsWith("__macosx/") || baseName.startsWith("._")) {
                    continue
                }
                when {
                    name.endsWith(".json") -> {
                        sawJson = true
                        action(Limited(zip, budget))
                    }
                    name.endsWith(".txt") -> sawTxt = true
                }
            }
        } catch (e: ZipException) {
            throw ExportFormatException(ExportFormatException.Reason.MALFORMED, e.message)
        }
        if (!sawJson) {
            throw ExportFormatException(
                if (sawTxt) ExportFormatException.Reason.TXT_FORMAT
                else ExportFormatException.Reason.NOT_AN_EXPORT,
            )
        }
    }

    private class Budget(var remaining: Long)

    private open class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    /** Stops a zip bomb from exhausting the phone while the stream is parsed. */
    private class Limited(input: InputStream, private val budget: Budget) : NonClosing(input) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) spend(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) spend(n.toLong())
            return n
        }

        private fun spend(n: Long) {
            budget.remaining -= n
            if (budget.remaining < 0) {
                throw ExportFormatException(ExportFormatException.Reason.TOO_LARGE)
            }
        }
    }
}
