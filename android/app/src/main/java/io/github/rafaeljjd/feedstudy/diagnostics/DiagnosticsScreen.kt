package io.github.rafaeljjd.feedstudy.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.rafaeljjd.feedstudy.BuildConfig
import io.github.rafaeljjd.feedstudy.export.ExportStructureReport
import io.github.rafaeljjd.feedstudy.export.VideoIds
import io.github.rafaeljjd.feedstudy.rating.DEFAULT_PAGE_HOST
import io.github.rafaeljjd.feedstudy.rating.EmbedPlayer
import io.github.rafaeljjd.feedstudy.study.KnownPackages
import io.github.rafaeljjd.feedstudy.task.DeepLinks
import io.github.rafaeljjd.feedstudy.task.Handoff
import io.github.rafaeljjd.feedstudy.ui.StudyViewModel
import io.github.rafaeljjd.feedstudy.usage.LocalDateBucketer
import io.github.rafaeljjd.feedstudy.usage.SessionBuilder
import io.github.rafaeljjd.feedstudy.usage.UsageAggregator
import io.github.rafaeljjd.feedstudy.usage.UsageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private const val SAMPLE_VIDEO = "https://www.tiktok.com/@scout2015/video/6718335390845095173"
private const val MAX_EMBED_LOG = 30
/** A reserved name that nobody can own, for comparing embed behaviour across origins. */
private const val TEST_PAGE_HOST = "feedstudy.example.org"

data class DeepLinkResult(
    val secondsAway: Long? = null,
    val openedExactVideo: Boolean? = null,
    val notInterestedReachable: Boolean? = null,
)

/** Test results, kept while the app is in the background during a handoff. */
class DiagnosticsState {
    var videoUrl by mutableStateOf(SAMPLE_VIDEO)
    val deepLinks = mutableStateMapOf<String, DeepLinkResult>()
    var pendingForm by mutableStateOf<String?>(null)
    var pendingSinceMillis by mutableStateOf(0L)
    var embedRunning by mutableStateOf(false)
    var embedHost by mutableStateOf(DEFAULT_PAGE_HOST)
    val embedResults = mutableStateMapOf<String, String>()
    val embedLog = mutableStateListOf<String>()
    var embedReady by mutableStateOf(false)
    var embedError by mutableStateOf<String?>(null)
    var embedMaxSeconds by mutableStateOf(0.0)
    var embedPlaysOnScreen by mutableStateOf<Boolean?>(null)
    var usageReport by mutableStateOf<String?>(null)
    var structureReport by mutableStateOf<String?>(null)

    /** Plain text to share with the study team. It has no export content or video links. */
    fun report(context: Context): String = buildString {
        appendLine("Feed Study diagnostics report (${BuildConfig.VERSION_NAME})")
        appendLine("Android SDK ${Build.VERSION.SDK_INT}, device ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Installed:")
        KnownPackages.ALL.forEach {
            appendLine("- $it: ${if (Handoff.isInstalled(context, it)) "yes" else "no"}")
        }
        appendLine()
        appendLine("Deep links:")
        if (deepLinks.isEmpty()) appendLine("- not tested")
        deepLinks.toSortedMap().forEach { (form, r) ->
            appendLine(
                "- $form: away ${r.secondsAway ?: "?"}s, exact video=${yesNo(r.openedExactVideo)}, " +
                    "Not interested reachable=${yesNo(r.notInterestedReachable)}",
            )
        }
        appendLine()
        appendLine("Embed (video, page origin: result):")
        if (embedResults.isEmpty()) appendLine("- not tested")
        embedResults.toSortedMap().forEach { (test, result) -> appendLine("- $test: $result") }
        appendLine("Played on screen: ${yesNo(embedPlaysOnScreen)}")
        appendLine()
        appendLine(usageReport ?: "Usage: not read")
        appendLine()
        appendLine(structureReport ?: "Export structure: not read")
    }

    private fun yesNo(value: Boolean?) = when (value) {
        true -> "yes"
        false -> "no"
        null -> "not answered"
    }
}

@Composable
fun DiagnosticsScreen(vm: StudyViewModel) {
    val d = vm.diagnostics
    val context = LocalContext.current
    BackHandler { vm.showDiagnostics = false }

    LifecycleResumeEffect(Unit) {
        d.pendingForm?.let { form ->
            val seconds = (System.currentTimeMillis() - d.pendingSinceMillis) / 1000
            d.deepLinks[form] = (d.deepLinks[form] ?: DeepLinkResult()).copy(secondsAway = seconds)
            d.pendingForm = null
        }
        onPauseOrDispose { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = { vm.showDiagnostics = false }) { Text("Back to study") }

        DeepLinkSection(d)
        HorizontalDivider()
        EmbedSection(d)
        HorizontalDivider()
        UsageSection(d)
        HorizontalDivider()
        StructureSection(d)
        HorizontalDivider()

        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, d.report(context))
            context.startActivity(Intent.createChooser(send, "Share report"))
        }) { Text("Share report") }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Answer(question: String, value: Boolean?, onAnswer: (Boolean) -> Unit) {
    Text(question, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = value == true, onClick = { onAnswer(true) }, label = { Text("Yes") })
        FilterChip(selected = value == false, onClick = { onAnswer(false) }, label = { Text("No") })
    }
}

@Composable
private fun DeepLinkSection(d: DiagnosticsState) {
    val activity = LocalActivity.current
    SectionTitle("1. Deep link to TikTok")
    Text("Use a public video. Try each form, and answer the two questions after returning.")
    OutlinedTextField(
        value = d.videoUrl,
        onValueChange = { d.videoUrl = it },
        label = { Text("Video link") },
        modifier = Modifier.fillMaxWidth(),
    )
    val id = VideoIds.fromUrl(d.videoUrl)
    if (id == null) {
        Text("That link has no video ID.", color = MaterialTheme.colorScheme.error)
        return
    }
    val targets = KnownPackages.TIKTOK_APPS + if (BuildConfig.DEBUG) listOf(KnownPackages.CHROME) else emptyList()
    val target = activity?.let { Handoff.firstInstalled(it, targets) }
    Text("Opens in: ${target ?: "no target app installed"}")

    DeepLinks.candidateForms(id, d.videoUrl).forEach { (form, url) ->
        val result = d.deepLinks[form] ?: DeepLinkResult()
        OutlinedButton(
            enabled = target != null,
            onClick = {
                val host = activity ?: return@OutlinedButton
                d.pendingForm = form
                d.pendingSinceMillis = System.currentTimeMillis()
                d.deepLinks[form] = result
                if (!Handoff.open(host, url, checkNotNull(target))) {
                    d.pendingForm = null
                    d.deepLinks[form] = result.copy(openedExactVideo = false)
                }
            },
        ) { Text("Open: $form") }
        if (form in d.deepLinks) {
            result.secondsAway?.let { Text("Away for $it seconds") }
            Answer("Did this open the exact video?", result.openedExactVideo) {
                d.deepLinks[form] = result.copy(openedExactVideo = it)
            }
            Answer("Could you reach Not interested on that screen?", result.notInterestedReachable) {
                d.deepLinks[form] = result.copy(notInterestedReachable = it)
            }
        }
    }
}

@Composable
private fun EmbedSection(d: DiagnosticsState) {
    SectionTitle("2. Embedded player")
    val id = VideoIds.fromUrl(d.videoUrl)
    Button(enabled = id != null, onClick = {
        d.embedRunning = !d.embedRunning
        d.embedLog.clear()
        d.embedReady = false
        d.embedError = null
        d.embedMaxSeconds = 0.0
    }) { Text(if (d.embedRunning) "Stop player" else "Start player") }
    Text("Page origin (stop the player before changing):")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(DEFAULT_PAGE_HOST to "App assets", TEST_PAGE_HOST to "Test domain").forEach { (host, label) ->
            FilterChip(
                selected = d.embedHost == host,
                enabled = !d.embedRunning,
                onClick = { d.embedHost = host },
                label = { Text(label) },
            )
        }
    }

    if (d.embedRunning && id != null) {
        val status = "Ready: ${d.embedReady}. Played to: ${d.embedMaxSeconds}s. Error: ${d.embedError ?: "none"}"
        LaunchedEffect(id, d.embedHost, status) { d.embedResults["$id, ${d.embedHost}"] = status }
        Text(status)
        EmbedPlayer(
            videoId = id,
            pageHost = d.embedHost,
            modifier = Modifier.fillMaxWidth().height(420.dp),
            onEvent = { event ->
                when (event) {
                    io.github.rafaeljjd.feedstudy.rating.PlayerEvent.Ready -> d.embedReady = true
                    is io.github.rafaeljjd.feedstudy.rating.PlayerEvent.Time ->
                        d.embedMaxSeconds = maxOf(d.embedMaxSeconds, event.currentSeconds)
                    is io.github.rafaeljjd.feedstudy.rating.PlayerEvent.Error ->
                        d.embedError = event.code.toString()
                    else -> Unit
                }
            },
            onRawMessage = { message ->
                Log.i("FeedStudyPlayer", message.take(200))
                if (d.embedLog.size < MAX_EMBED_LOG) d.embedLog.add(message.take(160))
            },
        )
        Answer("Did the video play on screen?", d.embedPlaysOnScreen) { d.embedPlaysOnScreen = it }
        d.embedLog.takeLast(5).forEach {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun UsageSection(d: DiagnosticsState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionTitle("3. Usage logging")
    Text("Compare today's minutes with Settings > Digital Wellbeing.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }) { Text("Usage access") }
        Button(onClick = {
            scope.launch {
                d.usageReport = withContext(Dispatchers.IO) { readUsageReport(context) }
            }
        }) { Text("Read usage") }
    }
    d.usageReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

private fun readUsageReport(context: Context): String {
    val reader = UsageReader(context)
    if (!reader.hasAccess()) return "Usage: access not granted"

    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val start = now - 30L * 86_400_000L
    val tracked = KnownPackages.ALL.toSet()
    val events = reader.events(start, now, tracked)
    val sessions = SessionBuilder.build(events.events, tracked).closed
    val fromEvents = UsageAggregator.aggregate(sessions, LocalDateBucketer(zone))
    val daily = reader.dailyStats(start, now, tracked)

    fun day(millis: Long?) = millis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: "none"

    return buildString {
        appendLine("Usage (asked for 30 days back)")
        appendLine("Oldest event, any app: ${day(events.earliestAnyEventMillis)}")
        appendLine("Oldest daily total, any app: ${day(daily.earliestBucketMillis)}")
        appendLine("Minutes from events:")
        if (fromEvents.isEmpty()) appendLine("- none")
        fromEvents.takeLast(21).forEach {
            appendLine("- ${it.day} ${it.packageName}: ${"%.1f".format(it.foregroundSeconds / 60.0)}, ${it.sessions} sessions")
        }
        appendLine("Minutes from Android daily totals:")
        if (daily.rows.isEmpty()) appendLine("- none")
        daily.rows.takeLast(21).forEach {
            appendLine("- ${it.day} ${it.packageName}: ${"%.1f".format(it.foregroundSeconds / 60.0)}")
        }
    }
}

@Composable
private fun StructureSection(d: DiagnosticsState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                d.structureReport = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use(ExportStructureReport::build)
                    }.getOrNull() ?: "Export structure: file could not be opened"
                }
            }
        }
    }
    SectionTitle("4. Export structure")
    Text("Reads your real TikTok export on this phone. The report has key names, counts and date ranges, with no content. Read it before sharing.")
    Button(onClick = {
        picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain"))
    }) { Text("Select export file") }
    d.structureReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
