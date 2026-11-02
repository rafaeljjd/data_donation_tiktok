package io.github.rafaeljjd.feedstudy

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TAG = "FeedStudyE2E"
private const val STEP_TIMEOUT = 60_000L
private const val POLL_MILLIS = 2_000L

/** Drives the installed app as a participant would. Needs the dev server: see scripts/e2e.sh. */
@RunWith(AndroidJUnit4::class)
class FullFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext
    private val packageName: String = context.packageName
    private var shotNumber = 0

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pipe ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).readBytes()
        }
    }

    private fun bringAppToFront() {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor(By.pkg(packageName))
    }

    private fun shot(name: String) {
        shotNumber += 1
        val directory = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        device.takeScreenshot(File(directory, "%02d_%s.png".format(shotNumber, name)))
    }

    /**
     * On an overloaded machine, Android shows "isn't responding" dialogs that hide the app from
     * UI Automator. Choosing Wait changes nothing in the app. Each one is logged, because one
     * naming Feed Study could be a real freeze and must be looked at.
     */
    private fun dismissNotRespondingDialog() {
        val dialog = device.findObject(By.textContains("isn't responding")) ?: return
        val text = runCatching { dialog.text }.getOrNull()
        Log.w(TAG, "System dialog: $text")
        runCatching { device.findObject(By.text("Wait"))?.click() }
    }

    private fun waitOrNull(selector: BySelector, timeout: Long): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            dismissNotRespondingDialog()
            device.wait(Until.findObject(selector), POLL_MILLIS)?.let { return it }
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun waitFor(selector: BySelector, timeout: Long = STEP_TIMEOUT): UiObject2 =
        checkNotNull(waitOrNull(selector, timeout)) { "Timed out waiting for $selector" }

    private fun find(selector: BySelector): UiObject2 {
        device.findObject(selector)?.let { return it }
        device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(selector))
            ?.let { return it }
        return waitFor(selector, 5_000)
    }

    private fun tap(text: String) {
        waitForAny(text)
        // The screen can redraw between finding a button and clicking it.
        var last: Throwable? = null
        repeat(3) {
            try {
                find(By.text(text)).click()
                return
            } catch (e: StaleObjectException) {
                last = e
            }
        }
        throw checkNotNull(last)
    }

    private fun waitForAny(text: String, timeout: Long = STEP_TIMEOUT) {
        waitFor(By.textContains(text), timeout)
    }

    @Before
    fun setUp() {
        shell("appops set $packageName GET_USAGE_STATS allow")
        shell("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        bringAppToFront()
    }

    @Test
    fun walksTheWholeStudy() {
        // --- Baseline
        waitForAny("Welcome to Feed Study")
        shot("enroll")
        waitFor(By.clazz("android.widget.EditText")).text = "E2E_${System.currentTimeMillis()}"
        tap("Continue")

        waitForAny("About this study")
        shot("consent")
        find(By.checkable(true)).click()
        tap("Agree and continue")

        waitForAny("Allow usage access")
        waitForAny("Usage access is on")
        shot("usage_access")
        tap("Next")

        waitForAny("Request your TikTok data")
        shot("export_guide")
        tap("I have requested my data")

        waitForAny("Add your TikTok data file")
        shot("export_import")
        tap("Dev: use synthetic export")

        val baselineFlagged = rateAll("baseline")
        Log.i(TAG, "Flagged at baseline: $baselineFlagged")
        answerPriceLists("baseline")

        waitForAny("One more question")
        shot("filter_wtp")
        waitFor(By.clazz("android.widget.EditText")).text = "5"
        tap("Continue")

        // --- Randomized task. Chrome stands in for TikTok on the emulator.
        val total = doTask()
        Log.i(TAG, "Task videos: $total")

        // --- Tracking, on the compressed clock
        waitForAny("You are all set for now")
        shot("tracking")
        tap("Dev: sync usage now")
        waitForAny("Request your TikTok data again", timeout = 180_000)

        // --- Endline
        shot("export_guide_2")
        tap("I have requested my data")
        waitForAny("Add your TikTok data file")
        tap("Dev: use synthetic export")

        val endlineFlagged = rateAll("endline")
        Log.i(TAG, "Flagged at endline: $endlineFlagged")
        answerPriceLists("endline")

        val thanks = waitFor(By.text("Thank you"), 60_000)
        assertEquals("Thank you", thanks.text)
        shot("done")
        val code = waitFor(By.text(java.util.regex.Pattern.compile("[0-9A-F]{8}")), 10_000).text
        Log.i(TAG, "Completion code: $code")
        assertTrue(code.isNotBlank())
    }

    /** Answers from the synthetic topic: topic 0 stands for unwanted content. */
    private fun rateAll(label: String): Int {
        var flagged = 0
        var first = true
        while (true) {
            waitFor(By.pkg(packageName))
            val title = waitOrNull(By.textStartsWith("Video "), 5_000)
            if (title == null) {
                if (device.hasObject(By.textStartsWith("Stop using"))) return flagged
                continue
            }
            val heading = runCatching { title.text }.getOrNull() ?: continue
            val topic = runCatching {
                waitFor(By.textStartsWith("Topic "), 5_000).text.removePrefix("Topic ").toInt()
            }.getOrNull() ?: continue
            if (first) {
                shot("rating_$label")
                first = false
            }
            val wantsToStop = topic == 0
            if (wantsToStop) flagged += 1
            tap(if (wantsToStop) "Yes" else "No")
            waitUntilGone(heading)
            if (device.hasObject(By.textStartsWith("Stop using"))) return flagged
        }
    }

    private fun waitUntilGone(text: String) {
        device.wait(Until.gone(By.text(text)), STEP_TIMEOUT)
    }

    private fun answerPriceLists(label: String) {
        repeat(3) { index ->
            val title = waitFor(By.textStartsWith("Stop using")).text
            if (index == 0) shot("wta_$label")
            find(By.text("\$10")).click()
            tap("Confirm")
            waitUntilGone(title)
        }
    }

    private fun doTask(): Int {
        waitFor(By.textStartsWith("Video 1 of"), 60_000)
        shot("task")
        var count = 0
        while (true) {
            val title = waitOrNull(By.textStartsWith("Video "), 5_000)
                ?: return count
            val heading = runCatching { title.text }.getOrNull() ?: continue
            tap("Open in TikTok")
            // Stay in the target app for a few seconds, so foreground time is measurable.
            device.wait(Until.gone(By.text("Open in TikTok")), 10_000)
            SystemClock.sleep(4_000)
            bringAppToFront()
            waitForAny("Did you tap Not interested")
            if (count == 0) shot("task_returned")
            tap("Yes, I tapped it")
            waitUntilGone(heading)
            count += 1
        }
    }
}
