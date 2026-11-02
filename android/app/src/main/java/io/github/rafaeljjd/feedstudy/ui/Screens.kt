package io.github.rafaeljjd.feedstudy.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeljjd.feedstudy.BuildConfig
import io.github.rafaeljjd.feedstudy.data.RatingItem
import io.github.rafaeljjd.feedstudy.data.RatingStatus
import io.github.rafaeljjd.feedstudy.data.StudyState
import io.github.rafaeljjd.feedstudy.data.TaskItem
import io.github.rafaeljjd.feedstudy.diagnostics.DiagnosticsScreen
import io.github.rafaeljjd.feedstudy.rating.EmbedPlayer
import io.github.rafaeljjd.feedstudy.rating.PlayerEvent
import io.github.rafaeljjd.feedstudy.study.Goods
import io.github.rafaeljjd.feedstudy.study.KnownPackages
import io.github.rafaeljjd.feedstudy.study.Phase
import io.github.rafaeljjd.feedstudy.study.Rounds
import io.github.rafaeljjd.feedstudy.study.StudyClock
import io.github.rafaeljjd.feedstudy.study.StudyConfig
import io.github.rafaeljjd.feedstudy.task.Handoff
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PLAYER_LOAD_TIMEOUT_MILLIS = 20_000L

@Composable
fun AppRoot(vm: StudyViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state
    val config = current?.let(vm.repository::config)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            vm.showDiagnostics -> DiagnosticsScreen(vm)
            current == null -> Loading()
            config == null -> EnrollScreen(vm)
            else -> PhaseScreen(current, config, vm)
        }
    }
}

@Composable
private fun PhaseScreen(state: StudyState, config: StudyConfig, vm: StudyViewModel) {
    when (state.phase) {
        Phase.ENROLL -> EnrollScreen(vm)
        Phase.CONSENT -> ConsentScreen(vm)
        Phase.USAGE_ACCESS -> UsageAccessScreen(vm)
        Phase.EXPORT_GUIDE_1 -> ExportGuideScreen(Rounds.BASELINE, vm)
        Phase.EXPORT_IMPORT_1 -> ExportImportScreen(Rounds.BASELINE, config, vm)
        Phase.RATING_1 -> RatingScreen(Rounds.BASELINE, config, vm)
        Phase.VALUATION_1 -> ValuationScreen(Rounds.BASELINE, state, config, vm)
        Phase.ASSIGNING -> AssigningScreen(vm)
        Phase.TASK -> TaskScreen(state, config, vm)
        Phase.TRACKING -> TrackingScreen(state, config, vm)
        Phase.EXPORT_GUIDE_2 -> ExportGuideScreen(Rounds.ENDLINE, vm)
        Phase.EXPORT_IMPORT_2 -> ExportImportScreen(Rounds.ENDLINE, config, vm)
        Phase.RATING_2 -> RatingScreen(Rounds.ENDLINE, config, vm)
        Phase.VALUATION_2 -> ValuationScreen(Rounds.ENDLINE, state, config, vm)
        Phase.FINISHING -> FinishingScreen(vm)
        Phase.DONE -> DoneScreen(state)
        Phase.INELIGIBLE -> IneligibleScreen(state, vm)
    }
}

// --- Shared layout

@Composable
private fun Page(
    title: String,
    vm: StudyViewModel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (vm.busy) CircularProgressIndicator()
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun DevNote(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.tertiary,
)

@Composable
private fun DiagnosticsLink(vm: StudyViewModel) {
    if (BuildConfig.DEBUG) {
        TextButton(onClick = { vm.showDiagnostics = true }) { Text("Diagnostics") }
    }
}

// --- Enrolment

@Composable
private fun EnrollScreen(vm: StudyViewModel) {
    var prolificId by rememberSaveable { mutableStateOf("") }
    Page("Welcome to Feed Study", vm) {
        Body("Enter your Prolific ID to begin.")
        OutlinedTextField(
            value = prolificId,
            onValueChange = { prolificId = it },
            label = { Text("Prolific ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.run { vm.repository.enroll(prolificId) } },
            enabled = !vm.busy && prolificId.isNotBlank(),
        ) { Text("Continue") }
        DiagnosticsLink(vm)
    }
}

@Composable
private fun ConsentScreen(vm: StudyViewModel) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    Page("About this study", vm) {
        DevNote("PLACEHOLDER TEXT. Not approved by an ethics board. Do not use with participants.")
        Body("This study lasts about five weeks. You will share your TikTok data file, answer questions about some videos you watched, and do a short task in TikTok.")
        Body("What leaves your phone: your answers, your daily time on TikTok and Instagram, and the IDs of videos you watched during the study periods, with how many times you watched each.")
        Body("What stays on your phone: your TikTok data file and the dates you watched each video. The app does not read your profile, messages or searches.")
        Body("What TikTok sees: videos shown in this app load from TikTok, so TikTok receives your IP address and the video's ID.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I have read this and I agree to take part.")
        }
        Button(
            onClick = { vm.run { vm.repository.acceptConsent() } },
            enabled = agreed && !vm.busy,
        ) { Text("Agree and continue") }
    }
}

@Composable
private fun UsageAccessScreen(vm: StudyViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = vm.repository.usageReader.hasAccess()
        onPauseOrDispose { }
    }
    Page("Allow usage access", vm) {
        Body("Feed Study records how much time you spend on TikTok and Instagram each day. It does not see what you do inside those apps.")
        if (granted) {
            Body("Usage access is on.")
            Button(
                onClick = { vm.run { vm.repository.confirmUsageAccess() } },
                enabled = !vm.busy,
            ) { Text("Next") }
        } else {
            Body("Tap the button, find Feed Study in the list, and turn on usage access. Then come back here.")
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("Open settings") }
        }
    }
}

// --- Export

@Composable
private fun ExportGuideScreen(round: Int, vm: StudyViewModel) {
    val activity = LocalActivity.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val title = if (round == Rounds.BASELINE) "Request your TikTok data" else "Request your TikTok data again"
    Page(title, vm) {
        Body("1. Open TikTok and go to your profile.")
        Body("2. Open the menu, then Settings and privacy.")
        Body("3. Go to Privacy and safety, then Personalization and data, then Download your data.")
        Body("4. Choose All data and the JSON format. TXT will not work.")
        Body("5. Tap Request data.")
        Body("TikTok can take a few days to prepare the file. We cannot see when it is ready, so we will remind you each day to check. The download may expire after a few days, so download it soon after it is ready.")
        OutlinedButton(onClick = {
            val target = activity?.let { Handoff.firstInstalled(it, KnownPackages.TIKTOK_APPS) }
            if (target == null || !Handoff.launchApp(activity, target)) {
                vm.showError("TikTok does not seem to be installed on this phone.")
            }
        }) { Text("Open TikTok") }
        Button(
            onClick = { vm.run { vm.repository.exportRequested() } },
            enabled = !vm.busy,
        ) { Text("I have requested my data") }
    }
}

@Composable
private fun ExportImportScreen(round: Int, config: StudyConfig, vm: StudyViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(round, uri)
    }
    Page("Add your TikTok data file", vm) {
        Body("When TikTok says your file is ready, download it on this phone. Then select it here.")
        Body("If the download has expired, request your data again in TikTok.")
        Body("The file is read on your phone and is not uploaded.")
        Button(
            onClick = {
                picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain"))
            },
            enabled = !vm.busy,
        ) { Text("Select file") }
        if (config.devMode) {
            OutlinedButton(
                onClick = { vm.run { vm.repository.importSyntheticExport(round) } },
                enabled = !vm.busy,
            ) { Text("Dev: use synthetic export") }
        }
    }
}

// --- Rating

@Composable
private fun RatingScreen(round: Int, config: StudyConfig, vm: StudyViewModel) {
    val items by vm.ratings(round).collectAsStateWithLifecycle(initialValue = null)
    val list = items
    val current = list?.firstOrNull { it.status == RatingStatus.PENDING }
    if (list == null || current == null) {
        Loading()
        return
    }
    val rated = list.count { it.status == RatingStatus.RATED }
    val pending = list.count { it.status == RatingStatus.PENDING }
    val total = minOf(config.ratingSampleSize, rated + pending)

    key(current.videoId) {
        RatingCard(round, current, rated + 1, total, config, vm)
    }
}

@Composable
private fun RatingCard(
    round: Int,
    item: RatingItem,
    number: Int,
    total: Int,
    config: StudyConfig,
    vm: StudyViewModel,
) {
    var watched by remember { mutableDoubleStateOf(0.0) }
    var ended by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }
    var waited by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(PLAYER_LOAD_TIMEOUT_MILLIS)
        // Covers a player that never loads, and one that loads but stalls before the minimum
        // watch time. Either would otherwise leave the answer buttons disabled for good.
        if (!config.syntheticPlayer && !ended && watched < config.minWatchSeconds) showSkip = true
    }
    LaunchedEffect(Unit) {
        if (config.syntheticPlayer) {
            delay(config.minWatchSeconds * 1000L)
            waited = true
        }
    }
    val canAnswer = !vm.busy && (waited || ended || watched >= config.minWatchSeconds)

    Page("Video $number of $total", vm) {
        if (config.syntheticPlayer) {
            SyntheticPlayer(item.videoId)
        } else {
            EmbedPlayer(
                videoId = item.videoId,
                modifier = Modifier.fillMaxWidth().height(440.dp),
                onEvent = { event ->
                    when (event) {
                        PlayerEvent.Ready -> Unit
                        is PlayerEvent.Time -> watched = maxOf(watched, event.currentSeconds)
                        is PlayerEvent.StateChange ->
                            if (event.state == PlayerEvent.STATE_ENDED) ended = true
                        is PlayerEvent.Error -> if (event.code != PlayerEvent.ERROR_AUTOPLAY) {
                            // A deleted video is replaced from the reserve and is not in h.
                            vm.run {
                                vm.repository.markUnavailable(round, item.videoId, event.code.toString())
                            }
                        }
                    }
                },
            )
        }
        Body("Do you want to stop seeing this kind of content?")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = canAnswer,
                onClick = {
                    vm.run { vm.repository.answerRating(round, item.videoId, true, watched) }
                },
            ) { Text("Yes") }
            Button(
                enabled = canAnswer,
                onClick = {
                    vm.run { vm.repository.answerRating(round, item.videoId, false, watched) }
                },
            ) { Text("No") }
        }
        if (showSkip) {
            TextButton(onClick = {
                vm.run { vm.repository.markUnavailable(round, item.videoId, "load_timeout") }
            }) { Text("This video will not load") }
        }
    }
}

/** Development only. Stands in for the player, and shows the fixture's built-in topic. */
@Composable
private fun SyntheticPlayer(videoId: String) {
    val topic = videoId.toBigInteger().mod(4.toBigInteger()).toInt()
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DevNote("Dev: synthetic video")
            Text("Topic $topic")
        }
    }
}

// --- Valuations

@Composable
private fun ValuationScreen(round: Int, state: StudyState, config: StudyConfig, vm: StudyViewModel) {
    val answers by vm.valuations(round).collectAsStateWithLifecycle(initialValue = null)
    val answered = answers
    if (answered == null) {
        Loading()
        return
    }
    val good = Goods.orderFor(state.samplingSeed).firstOrNull { g -> answered.none { it.good == g } }
    when {
        good != null -> key(good) { PriceList(round, good, config, vm) }
        round == Rounds.BASELINE -> FilterQuestion(round, config, vm)
        else -> {
            LaunchedEffect(Unit) { vm.run { vm.repository.submitValuations(round, null) } }
            Loading()
        }
    }
}

@Composable
private fun PriceList(round: Int, good: String, config: StudyConfig, vm: StudyViewModel) {
    var choice by rememberSaveable { mutableStateOf<Int?>(null) }
    val prices = config.priceList
    val noneIndex = prices.size

    Page("Stop using ${Goods.label(good)}", vm) {
        Body("Suppose we paid you to stop using ${Goods.label(good)} for ${config.deactivationDays} days. What is the smallest amount you would accept?")
        DevNote("Prototype: placeholder amounts. No payment will be made.")
        Column {
            (prices.indices + noneIndex).forEach { index ->
                val label = if (index == noneIndex) {
                    "None of these amounts"
                } else {
                    "${config.currencySymbol}${formatPrice(prices[index])}"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = choice == index,
                            role = Role.RadioButton,
                            onClick = { choice = index },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = choice == index, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            }
        }
        Button(
            enabled = choice != null && !vm.busy,
            onClick = {
                val picked = choice ?: return@Button
                val price = if (picked == noneIndex) null else prices[picked]
                vm.run { vm.repository.answerValuation(round, good, price) }
            },
        ) { Text("Confirm") }
    }
}

private fun formatPrice(price: Double): String =
    if (price % 1.0 == 0.0) price.toLong().toString() else "%.2f".format(price)

@Composable
private fun FilterQuestion(round: Int, config: StudyConfig, vm: StudyViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    val value = text.toDoubleOrNull()?.takeIf { it >= 0 }
    Page("One more question", vm) {
        Body("Suppose a filter removed the content you do not want from your TikTok feed. What is the most you would pay for it each month?")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Amount in ${config.currencySymbol}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = value != null && !vm.busy,
            onClick = { vm.run { vm.repository.submitValuations(round, value) } },
        ) { Text("Continue") }
    }
}

// --- Randomization and task

@Composable
private fun AssigningScreen(vm: StudyViewModel) {
    LaunchedEffect(Unit) { vm.run { vm.repository.assign() } }
    Page("Preparing your task", vm) {
        Body("This takes a moment.")
        if (vm.error != null) {
            Button(onClick = { vm.run { vm.repository.assign() } }, enabled = !vm.busy) {
                Text("Try again")
            }
        }
    }
}

/** The same screen and wording for every participant. It holds no information about the arm. */
@Composable
private fun TaskScreen(state: StudyState, config: StudyConfig, vm: StudyViewModel) {
    val items by vm.taskItems().collectAsStateWithLifecycle(initialValue = null)
    LifecycleResumeEffect(Unit) {
        vm.run { vm.repository.onAppResumed() }
        onPauseOrDispose { }
    }
    val list = items
    val current = list?.firstOrNull { it.selfReport == null }
    if (list == null || current == null) {
        Loading()
        return
    }
    key(current.videoId) {
        TaskCard(current, current.position + 1, list.size, state.hideCreator, config, vm)
    }
}

@Composable
private fun TaskCard(
    item: TaskItem,
    number: Int,
    total: Int,
    hideCreator: Boolean,
    config: StudyConfig,
    vm: StudyViewModel,
) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val hasReturned = item.handoffAtMillis != null && item.returnedAtMillis != null

    fun open() {
        val host = activity ?: return
        val target = Handoff.firstInstalled(host, config.handoffPackages)
        if (target == null) {
            vm.showError("TikTok does not seem to be installed on this phone.")
            return
        }
        scope.launch {
            // Saved first, so the time away is measured from before TikTok opens.
            vm.repository.recordHandoff(item.videoId, target)
            if (!Handoff.open(host, item.url, target)) {
                vm.showError("TikTok could not open this video.")
            }
        }
    }

    Page("Video $number of $total", vm) {
        Body("1. Tap Open in TikTok.")
        Body("2. Press and hold on the video.")
        if (hideCreator) {
            Body("3. Tap Not interested, then hide videos from this creator.")
        } else {
            Body("3. Tap Not interested.")
        }
        Body("4. Come back to this app.")
        if (hasReturned) {
            Body("Did you tap Not interested on that video?")
            Button(onClick = { vm.run { vm.repository.recordSelfReport(item.videoId, "sent") } }) {
                Text("Yes, I tapped it")
            }
            OutlinedButton(onClick = {
                vm.run { vm.repository.recordSelfReport(item.videoId, "not_found") }
            }) { Text("I could not find it") }
            TextButton(onClick = ::open) { Text("Open again") }
        } else {
            Button(onClick = ::open) { Text("Open in TikTok") }
        }
    }
}

// --- Tracking

@Composable
private fun TrackingScreen(state: StudyState, config: StudyConfig, vm: StudyViewModel) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hasAccess by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        hasAccess = vm.repository.usageReader.hasAccess()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            vm.repository.checkTrackingElapsed()
            delay(if (config.isCompressedClock) 1_000 else 60_000)
        }
    }
    val treatedAt = state.treatedAtMillis ?: now
    val day = StudyClock.dayNumber(now, treatedAt, config.daySeconds) + 1

    Page("You are all set for now", vm) {
        Body("Day ${minOf(day, config.trackingDays.toLong())} of ${config.trackingDays}.")
        Body("There is nothing to do. Use your phone as usual and keep this app installed. We will notify you when it is time for the last part.")
        if (!hasAccess) {
            Text(
                "Usage access was turned off. Please turn it back on so the study can continue.",
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("Open settings") }
        }
        if (config.devMode) {
            OutlinedButton(onClick = { vm.run { vm.repository.backgroundSync() } }) {
                Text("Dev: sync usage now")
            }
        }
        DiagnosticsLink(vm)
    }
}

// --- Completion

@Composable
private fun FinishingScreen(vm: StudyViewModel) {
    LaunchedEffect(Unit) { vm.run { vm.repository.finish() } }
    Page("Finishing up", vm) {
        Body("Sending your last answers.")
        if (vm.error != null) {
            Button(onClick = { vm.run { vm.repository.finish() } }, enabled = !vm.busy) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun DoneScreen(state: StudyState) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Thank you", style = MaterialTheme.typography.headlineSmall)
        Body("You have finished the study. Enter this code on Prolific:")
        Text(state.completionCode.orEmpty(), style = MaterialTheme.typography.headlineMedium)
        Body("You can now uninstall Feed Study. You can also delete your TikTok data file from your phone.")
    }
}

@Composable
private fun IneligibleScreen(state: StudyState, vm: StudyViewModel) {
    LaunchedEffect(state.completionCode) {
        if (state.completionCode == null) vm.run { vm.repository.finish() }
    }
    Page("Thank you", vm) {
        Body("You do not meet the requirements for the next part of this study. You do not need to do anything else.")
        state.completionCode?.let {
            Body("Enter this code on Prolific:")
            Text(it, style = MaterialTheme.typography.headlineMedium)
        }
        if (vm.error != null) {
            Button(onClick = { vm.run { vm.repository.finish() } }, enabled = !vm.busy) {
                Text("Try again")
            }
        }
    }
}
