package io.github.rafaeljjd.feedstudy

import io.github.rafaeljjd.feedstudy.export.ExportDates
import io.github.rafaeljjd.feedstudy.export.ExportFormatException
import io.github.rafaeljjd.feedstudy.export.ExportParser
import io.github.rafaeljjd.feedstudy.export.ExportStructureReport
import io.github.rafaeljjd.feedstudy.export.RecordKind
import io.github.rafaeljjd.feedstudy.export.VideoIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportParserTest {
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "Run fixtures/generate_export.py" }

    private val legacy = """
        {"Activity":{
          "Video Browsing History":{"VideoList":[
            {"Date":"2026-10-01 12:00:00","Link":"https://www.tiktokv.com/share/video/7000000000000000001/"},
            {"Date":"2026-10-02 12:00:00","Link":"https://www.tiktokv.com/share/video/7000000000000000002/"}
          ]},
          "Like List":{"ItemFavoriteList":[{"Date":"2026-10-01 13:00:00","Link":"https://www.tiktokv.com/share/video/7000000000000000001/"}]},
          "Favorite Videos":{"FavoriteVideoList":[{"Date":"2026-10-01 14:00:00","Link":"https://www.tiktokv.com/share/video/7000000000000000002/"}]}
        }}
    """.trimIndent()

    @Test fun readsLegacyNesting() {
        val parsed = ExportParser.parse(stream(legacy))
        assertEquals(2, parsed.stats.watchCount)
        assertEquals(1, parsed.stats.likeCount)
        assertEquals(1, parsed.stats.favoriteCount)
        assertEquals("7000000000000000001", parsed.records.first().videoId)
    }

    @Test fun readsModernNestingAndLowerCaseKeys() {
        val parsed = ExportParser.parse(
            stream(
                """{"Your Activity":{"Watch History":{"VideoList":[
                   {"date":"2026-10-01 12:00:00 UTC","link":"https://www.tiktok.com/@a/video/7000000000000000009","Post title":"private text"}
                   ]}},
                   "Likes and Favorites":{
                     "Like List":{"ItemFavoriteList":[{"date":"2026-10-01 12:00:00","link":"https://www.tiktok.com/@a/video/7000000000000000010"}]},
                     "Favorite Videos":{"FavoriteVideoList":[{"Date":"2026-10-01 12:00:00","Link":"https://www.tiktok.com/@a/video/7000000000000000011"}]}
                   }}""",
            ),
        )
        assertEquals(RecordKind.WATCH, parsed.records.single { it.videoId.endsWith("09") }.kind)
        assertEquals(RecordKind.LIKE, parsed.records.single { it.videoId.endsWith("10") }.kind)
        assertEquals(RecordKind.FAVORITE, parsed.records.single { it.videoId.endsWith("11") }.kind)
    }

    @Test fun ownPostedVideosAreNotWatchHistory() {
        val parsed = ExportParser.parse(
            stream(
                """{"Video":{"Videos":{"VideoList":[
                   {"Date":"2026-10-01 12:00:00","Link":"https://www.tiktok.com/@me/video/7000000000000000099","Likes":"3"}
                   ]}}}""",
            ),
        )
        assertEquals(0, parsed.stats.watchCount)
        assertEquals(1, parsed.stats.ignoredOtherRecords)
    }

    @Test fun neverKeepsTextFromDeniedSections() {
        val secret = "secret-chat-text-xyz"
        val parsed = ExportParser.parse(
            stream(
                """{"Direct Messages":{"Chat History":{"Chat History with bob:":[
                     {"Date":"2026-10-01 12:00:00","From":"bob","Content":"$secret",
                      "Link":"https://www.tiktok.com/@bob/video/7000000000000000050"}]}},
                   "Profile":{"Profile Information":{"Date":"2026-10-01 12:00:00","Link":"https://x/video/7000000000000000051"}},
                   "Watch History":{"VideoList":[{"Date":"2026-10-01 12:00:00","Link":"https://www.tiktok.com/@a/video/7000000000000000052"}]}}""",
            ),
        )
        assertEquals(listOf("7000000000000000052"), parsed.records.map { it.videoId })
        assertFalse(parsed.toString().contains(secret))
    }

    @Test fun countsLinksWithoutIdsAndBadDates() {
        val parsed = ExportParser.parse(
            stream(
                """{"Watch History":{"VideoList":[
                   {"Date":"2026-10-01 12:00:00","Link":"https://vm.tiktok.com/ZMabc/"},
                   {"Date":"not a date","Link":"https://www.tiktok.com/@a/video/7000000000000000052"},
                   {"Date":"2026-10-01 12:00:00","Link":"https://www.tiktok.com/@a/video/7000000000000000053"}
                   ]}}""",
            ),
        )
        assertEquals(1, parsed.stats.watchCount)
        assertEquals(1, parsed.stats.droppedNoVideoId)
        assertEquals(1, parsed.stats.droppedBadDate)
    }

    @Test fun readsJsonInsideZipAndIgnoresMacMetadata() {
        val parsed = ExportParser.parse(
            zip(
                "__MACOSX/._user_data.json" to "garbage",
                "export/user_data_tiktok.json" to legacy,
                "export/readme.txt" to "ignored",
            ),
        )
        assertEquals(2, parsed.stats.watchCount)
    }

    @Test fun rejectsTxtExports() {
        val zipError = assertThrows(ExportFormatException::class.java) {
            ExportParser.parse(zip("Activity/Browsing History.txt" to "Date: 2026-10-01"))
        }
        assertEquals(ExportFormatException.Reason.TXT_FORMAT, zipError.reason)

        val plainError = assertThrows(ExportFormatException::class.java) {
            ExportParser.parse(stream("Date: 2026-10-01\nLink: https://x"))
        }
        assertEquals(ExportFormatException.Reason.TXT_FORMAT, plainError.reason)
    }

    @Test fun rejectsTruncatedJson() {
        val error = assertThrows(ExportFormatException::class.java) {
            ExportParser.parse(stream("""{"Watch History":{"VideoList":[{"Date":"""))
        }
        assertEquals(ExportFormatException.Reason.MALFORMED, error.reason)
    }

    @Test fun zipWithoutJsonIsNotAnExport() {
        val error = assertThrows(ExportFormatException::class.java) {
            ExportParser.parse(zip("photo.png" to "x"))
        }
        assertEquals(ExportFormatException.Reason.NOT_AN_EXPORT, error.reason)
    }

    @Test fun extractsVideoIds() {
        assertEquals("7123456789", VideoIds.fromUrl("https://www.tiktokv.com/share/video/7123456789/"))
        assertEquals("7123456789", VideoIds.fromUrl("https://www.tiktok.com/@a.b/video/7123456789?lang=en"))
        assertEquals("7123456789", VideoIds.fromUrl("https://m.tiktok.com/v/7123456789.html"))
        assertNull(VideoIds.fromUrl("https://vm.tiktok.com/ZMabc/"))
    }

    @Test fun parsesDateVariants() {
        val expected = 1_790_856_000_000L // 2026-10-01T12:00:00Z
        assertEquals(expected, ExportDates.parseMillis("2026-10-01 12:00:00"))
        assertEquals(expected, ExportDates.parseMillis("2026-10-01 12:00:00 UTC"))
        assertEquals(expected, ExportDates.parseMillis("2026-10-01T12:00:00Z"))
        assertEquals(expected, ExportDates.parseMillis("2026-10-01T14:00:00+02:00"))
        assertNull(ExportDates.parseMillis("yesterday"))
    }

    @Test fun structureReportHasNoContent() {
        val report = ExportStructureReport.build(
            stream(
                """{"Direct Messages":{"Chat History":{"Chat History with bob:":[{"Content":"secret"}]}},
                   "Odd":{"user@example.com 12345":{"x":1}},
                   "Your Activity":{"Watch History":{"VideoList":[
                     {"Date":"2026-10-01 12:00:00","Link":"https://www.tiktok.com/@alice/video/7000000000000000052"}
                   ]}}}""",
            ),
        )
        assertTrue(report, report.contains("Your Activity > Watch History > VideoList: 1 records"))
        assertTrue(report, report.contains("Direct Messages"))
        for (leak in listOf("bob", "secret", "alice", "7000000000000000052", "user@example.com", "12345")) {
            assertFalse("Report leaked $leak: $report", report.contains(leak))
        }
    }

    @Test fun readsGeneratorFixtures() {
        val modern = ExportParser.parse(resource("export_modern.zip"))
        assertEquals(40, modern.stats.watchCount)
        assertEquals(5, modern.stats.likeCount)
        assertEquals(3, modern.stats.favoriteCount)
        assertNotNull(modern.stats.latestWatchMillis)

        val legacyFixture = ExportParser.parse(resource("export_legacy.json"))
        assertEquals(40, legacyFixture.stats.watchCount)

        val txt = assertThrows(ExportFormatException::class.java) {
            ExportParser.parse(resource("export_txt.zip"))
        }
        assertEquals(ExportFormatException.Reason.TXT_FORMAT, txt.reason)

        val activityOnly = ExportParser.parse(resource("export_no_watch_history.json"))
        assertEquals(0, activityOnly.stats.watchCount)
    }
}
