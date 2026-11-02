package io.github.rafaeljjd.feedstudy

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TAG = "FeedStudyEmbed"
private const val SAMPLE = "https://www.tiktok.com/@scout2015/video/6718335390845095173"

/**
 * Tries TikTok's real embed player in the app's WebView for each video and page origin.
 * Needs internet, so it is not part of the offline end-to-end run. Choose videos with:
 *   adb shell am instrument -w -e videos url1,url2 ...
 * It passes if at least one video reports ready, and logs every result.
 */
@RunWith(AndroidJUnit4::class)
class EmbedSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory = File(context.getExternalFilesDir(null), "embed").apply { mkdirs() }

    private fun scroller(): UiObject2 =
        checkNotNull(device.wait(Until.findObject(By.scrollable(true)), 10_000))

    /** Scrolls in whichever direction finds the text. */
    private fun bring(text: String): UiObject2 {
        val selector = By.text(text)
        device.findObject(selector)?.let { return it }
        scroller().scrollUntil(Direction.UP, Until.findObject(selector))?.let { return it }
        return checkNotNull(scroller().scrollUntil(Direction.DOWN, Until.findObject(selector))) {
            "Could not find $text"
        }
    }

    private fun runOne(url: String, hostLabel: String): String {
        val field = checkNotNull(
            device.findObject(By.clazz("android.widget.EditText"))
                ?: scroller().scrollUntil(Direction.UP, Until.findObject(By.clazz("android.widget.EditText"))),
        )
        field.text = url
        bring(hostLabel).click()
        bring("Start player").click()
        val status = checkNotNull(
            scroller().scrollUntil(Direction.DOWN, Until.findObject(By.textStartsWith("Ready:"))),
        )
        check(status.text.isNotEmpty())

        var last = ""
        for (attempt in 1..16) {
            SystemClock.sleep(3_000)
            last = device.findObject(By.textStartsWith("Ready:"))?.text ?: last
            val settled = last.contains("Error: 1") || last.contains("Error: 2") || last.contains("Error: 3")
            val playing = last.startsWith("Ready: true") && !last.contains("Played to: 0.0s")
            if (settled || playing) break
        }
        val id = url.substringAfterLast('/')
        device.takeScreenshot(File(directory, "${id}_${hostLabel.replace(' ', '_')}.png"))
        bring("Stop player").click()
        return last
    }

    @Test
    fun embedGrid() {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        checkNotNull(device.wait(Until.findObject(By.text("Diagnostics")), 60_000)).click()
        check(device.wait(Until.hasObject(By.text("Back to study")), 10_000))

        val arguments = InstrumentationRegistry.getArguments()
        val videos = arguments.getString("videos", SAMPLE).split(",")
        val results = videos.flatMap { url ->
            listOf("App assets", "Test domain").map { host ->
                val result = runCatching { runOne(url, host) }.getOrElse { "test error: ${it.message}" }
                Log.i(TAG, "RESULT ${url.substringAfterLast('/')} | $host | $result")
                result
            }
        }
        assertTrue("No video was ready: $results", results.any { it.startsWith("Ready: true") })
    }
}
