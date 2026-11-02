package io.github.rafaeljjd.feedstudy.study

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import io.github.rafaeljjd.feedstudy.BuildConfig
import io.github.rafaeljjd.feedstudy.data.ExportRecordEntity
import io.github.rafaeljjd.feedstudy.data.RatingItem
import io.github.rafaeljjd.feedstudy.data.RatingStatus
import io.github.rafaeljjd.feedstudy.data.StudyDatabase
import io.github.rafaeljjd.feedstudy.data.StudyState
import io.github.rafaeljjd.feedstudy.data.TaskItem
import io.github.rafaeljjd.feedstudy.data.UsageSessionEntity
import io.github.rafaeljjd.feedstudy.data.ValuationAnswer
import io.github.rafaeljjd.feedstudy.export.ExportFormatException
import io.github.rafaeljjd.feedstudy.export.ExportParser
import io.github.rafaeljjd.feedstudy.export.ExportRecord
import io.github.rafaeljjd.feedstudy.export.ParsedExport
import io.github.rafaeljjd.feedstudy.export.RecordKind
import io.github.rafaeljjd.feedstudy.net.ApiClient
import io.github.rafaeljjd.feedstudy.net.ConsentRequest
import io.github.rafaeljjd.feedstudy.net.EnrollRequest
import io.github.rafaeljjd.feedstudy.net.ExportSummary
import io.github.rafaeljjd.feedstudy.net.Outbox
import io.github.rafaeljjd.feedstudy.net.RatingRow
import io.github.rafaeljjd.feedstudy.net.RatingsRequest
import io.github.rafaeljjd.feedstudy.net.TaskLogRequest
import io.github.rafaeljjd.feedstudy.net.TaskLogRow
import io.github.rafaeljjd.feedstudy.net.UsageRequest
import io.github.rafaeljjd.feedstudy.net.UsageRow
import io.github.rafaeljjd.feedstudy.net.ValuationRow
import io.github.rafaeljjd.feedstudy.net.ValuationsRequest
import io.github.rafaeljjd.feedstudy.net.VideoRow
import io.github.rafaeljjd.feedstudy.net.VideosRequest
import io.github.rafaeljjd.feedstudy.task.DeepLinks
import io.github.rafaeljjd.feedstudy.usage.CompressedBucketer
import io.github.rafaeljjd.feedstudy.usage.DayBucketer
import io.github.rafaeljjd.feedstudy.usage.LocalDateBucketer
import io.github.rafaeljjd.feedstudy.usage.Session
import io.github.rafaeljjd.feedstudy.usage.SessionBuilder
import io.github.rafaeljjd.feedstudy.usage.UsageAggregator
import io.github.rafaeljjd.feedstudy.usage.UsageReader
import io.github.rafaeljjd.feedstudy.work.Notifier
import io.github.rafaeljjd.feedstudy.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** A problem the participant can act on. The message is shown on screen. */
class UserFacingException(message: String) : Exception(message)

object Rounds {
    const val BASELINE = 1
    const val ENDLINE = 2

    fun name(round: Int) = if (round == BASELINE) "baseline" else "endline"
}

object Goods {
    val ALL = listOf("tiktok", "instagram", "both")

    fun label(good: String) = when (good) {
        "tiktok" -> "TikTok"
        "instagram" -> "Instagram"
        else -> "both TikTok and Instagram"
    }

    /** Same order at baseline and endline for one person, random across people. */
    fun orderFor(seed: Long): List<String> = ALL.shuffled(Random(seed))
}

class StudyRepository(
    private val context: Context,
    private val db: StudyDatabase,
    private val api: ApiClient,
    val usageReader: UsageReader,
    private val scheduler: Scheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.dao()
    private val outbox = Outbox(dao, api)
    private val usageMutex = Mutex()
    private val ratingMutex = Mutex()

    val stateFlow: Flow<StudyState?> = dao.stateFlow()

    fun ratingsFlow(round: Int) = dao.ratingsFlow(round)
    fun valuationsFlow(round: Int) = dao.valuationsFlow(round)
    fun taskItemsFlow() = dao.taskItemsFlow()

    suspend fun ensureState() = dao.insertState(StudyState())

    private suspend fun state(): StudyState = checkNotNull(dao.state()) { "State not created" }

    private fun configOf(state: StudyState): StudyConfig =
        StudyJson.decodeFromString(checkNotNull(state.configJson) { "Not enrolled" })

    fun config(state: StudyState): StudyConfig? =
        state.configJson?.let { StudyJson.decodeFromString<StudyConfig>(it) }

    private suspend fun advance(event: StudyEvent) {
        val phase = state().phase
        val next = StudyMachine.next(phase, event) ?: return
        dao.movePhase(phase, next)
    }

    private suspend inline fun <reified T> enqueue(path: String, payload: T) =
        outbox.enqueue(path, StudyJson.encodeToString(payload))

    // --- Enrolment

    suspend fun enroll(prolificId: String) {
        val id = prolificId.trim()
        if (id.isEmpty()) throw UserFacingException("Enter your Prolific ID.")
        val response = api.enroll(EnrollRequest(id, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT))
        dao.saveEnrollment(
            participantId = response.participantId,
            token = response.token,
            configJson = StudyJson.encodeToString(response.config),
            enrolledAt = clock(),
            seed = Random.nextLong(),
        )
        advance(StudyEvent.ENROLLED)
    }

    suspend fun acceptConsent() {
        val config = configOf(state())
        enqueue("/v1/consent", ConsentRequest(config.consentVersion, clock()))
        outbox.flush()
        advance(StudyEvent.CONSENTED)
    }

    suspend fun confirmUsageAccess() {
        if (!usageReader.hasAccess()) {
            throw UserFacingException("Usage access is not on yet. Turn it on for Feed Study.")
        }
        syncUsage()
        scheduler.schedulePeriodicSync(configOf(state()).syncIntervalMinutes)
        advance(StudyEvent.USAGE_GRANTED)
    }

    // --- Export

    suspend fun exportRequested() {
        scheduler.scheduleExportReminder(configOf(state()).reminderIntervalSeconds)
        advance(StudyEvent.EXPORT_REQUESTED)
    }

    /** Called by the reminder worker. Returns false once the reminder is no longer needed. */
    suspend fun sendExportReminder(): Boolean {
        val phase = dao.state()?.phase ?: return false
        if (phase != Phase.EXPORT_IMPORT_1 && phase != Phase.EXPORT_IMPORT_2) return false
        Notifier.post(
            context,
            "Check your TikTok data",
            "Your TikTok file may be ready. Download it and add it to Feed Study.",
        )
        scheduler.scheduleExportReminder(configOf(state()).reminderIntervalSeconds)
        return true
    }

    suspend fun importExport(round: Int, input: InputStream) {
        val parsed = withContext(Dispatchers.IO) {
            try {
                ExportParser.parse(input)
            } catch (e: ExportFormatException) {
                throw UserFacingException(importErrorMessage(e.reason))
            }
        }
        if (parsed.stats.watchCount == 0) {
            throw UserFacingException(
                "This file has no watch history. In TikTok, request \"All data\" in JSON format, " +
                    "then add the new file.",
            )
        }
        if (round == Rounds.BASELINE) importBaseline(parsed) else importEndline(parsed)

        scheduler.cancelExportReminder()
        outbox.flush()
        finishRatingIfDone(round)
    }

    private fun importErrorMessage(reason: ExportFormatException.Reason) = when (reason) {
        ExportFormatException.Reason.TXT_FORMAT ->
            "This file is in TXT format. In TikTok, request your data again and choose JSON."
        ExportFormatException.Reason.NOT_AN_EXPORT ->
            "This does not look like a TikTok data file. Choose the zip file that TikTok gave you."
        ExportFormatException.Reason.MALFORMED ->
            "This file could not be read. It may be incomplete, so try downloading it again."
        ExportFormatException.Reason.TOO_LARGE ->
            "This file is too large to read on this phone."
    }

    private suspend fun importBaseline(parsed: ParsedExport) {
        val state = state()
        val config = configOf(state)
        val pool = Windows.baselinePool(parsed.records, config.lookbackDays * DAY_MILLIS)
        val sample = RatingSampler.draw(
            pool.map { SampledVideo(it.videoId, it.url) },
            config.ratingSampleSize,
            state.samplingSeed,
        )
        db.withTransaction {
            replaceRecords(Rounds.BASELINE, parsed.records)
            replaceRatings(Rounds.BASELINE, sample)
            enqueue(
                "/v1/videos",
                VideosRequest(
                    round = Rounds.name(Rounds.BASELINE),
                    summary = summary(parsed, windowDays = null),
                    videos = Windows.counts(pool, Windows.BASELINE_POOL).map {
                        VideoRow(it.videoId, it.window, it.watchCount)
                    },
                ),
            )
            advance(StudyEvent.EXPORT_IMPORTED)
        }
    }

    private suspend fun importEndline(parsed: ParsedExport) {
        val state = state()
        val config = configOf(state)
        val treatedAt = checkNotNull(state.treatedAtMillis) { "Endline before treatment" }
        val latest = parsed.stats.latestWatchMillis ?: 0
        if (latest < treatedAt) {
            throw UserFacingException(
                "This file ends before your study task, so it may be an old file. " +
                    "Request a new file in TikTok, then add it here.",
            )
        }
        val earlier = dao.allRecords().filter { it.round == Rounds.BASELINE }.map {
            ExportRecord(RecordKind.valueOf(it.kind), it.videoId, it.url, it.watchedAtMillis)
        }
        val windows = Windows.endline(
            records = earlier + parsed.records,
            baselineAtMillis = checkNotNull(state.enrolledAtMillis),
            treatedAtMillis = treatedAt,
            taskVideoIds = dao.taskItems().map { it.videoId }.toSet(),
        )
        val sample = RatingSampler.draw(
            windows.postPool,
            config.ratingSampleSize,
            state.samplingSeed + 1,
        )
        db.withTransaction {
            replaceRecords(Rounds.ENDLINE, parsed.records)
            replaceRatings(Rounds.ENDLINE, sample)
            enqueue(
                "/v1/videos",
                VideosRequest(
                    round = Rounds.name(Rounds.ENDLINE),
                    summary = summary(parsed, windowDays = windows.windowMillis.toDouble() / DAY_MILLIS),
                    videos = (windows.pre + windows.post).map {
                        VideoRow(it.videoId, it.window, it.watchCount)
                    },
                ),
            )
            advance(StudyEvent.EXPORT_IMPORTED)
        }
    }

    private suspend fun replaceRecords(round: Int, records: List<ExportRecord>) {
        // Keep only what the study windows can need.
        val latest = records.maxOfOrNull { it.watchedAtMillis } ?: return
        val oldest = latest - RETAIN_DAYS * DAY_MILLIS
        dao.deleteRecords(round)
        dao.insertRecords(
            records.filter { it.watchedAtMillis >= oldest }.map {
                ExportRecordEntity(
                    round = round,
                    kind = it.kind.name,
                    videoId = it.videoId,
                    url = it.url,
                    watchedAtMillis = it.watchedAtMillis,
                )
            },
        )
    }

    private suspend fun replaceRatings(round: Int, sample: List<SampledVideo>) {
        dao.deleteRatings(round)
        dao.insertRatings(sample.mapIndexed { i, v -> RatingItem(round, v.videoId, i) })
    }

    private fun summary(parsed: ParsedExport, windowDays: Double?) = ExportSummary(
        watchCount = parsed.stats.watchCount,
        likeCount = parsed.stats.likeCount,
        favoriteCount = parsed.stats.favoriteCount,
        droppedNoVideoId = parsed.stats.droppedNoVideoId,
        droppedBadDate = parsed.stats.droppedBadDate,
        ignoredOtherRecords = parsed.stats.ignoredOtherRecords,
        windowDays = windowDays,
    )

    /** Development only: the local server generates a synthetic export for this person. */
    suspend fun importSyntheticExport(round: Int) {
        val state = state()
        val token = checkNotNull(state.token)
        val query = buildString {
            append("/dev/export?round=${Rounds.name(round)}&now_millis=${clock()}")
            append("&enrolled_at_millis=${state.enrolledAtMillis}")
            state.treatedAtMillis?.let { append("&treated_at_millis=$it") }
        }
        val bytes = api.download(query, token) { it.readBytes() }
        importExport(round, bytes.inputStream())
    }

    // --- Rating

    suspend fun answerRating(round: Int, videoId: String, wantsToStop: Boolean, watchedSeconds: Double) =
        ratingMutex.withLock {
            dao.setRated(round, videoId, wantsToStop, watchedSeconds)
            finishRatingIfDone(round)
        }

    suspend fun markUnavailable(round: Int, videoId: String, errorCode: String) =
        ratingMutex.withLock {
            dao.setUnavailable(round, videoId, errorCode)
            finishRatingIfDone(round)
        }

    private suspend fun finishRatingIfDone(round: Int) {
        val state = state()
        val expected = if (round == Rounds.BASELINE) Phase.RATING_1 else Phase.RATING_2
        if (state.phase != expected) return

        val items = dao.ratings(round)
        val rated = items.count { it.status == RatingStatus.RATED }
        val pending = items.count { it.status == RatingStatus.PENDING }
        if (rated < configOf(state).ratingSampleSize && pending > 0) return

        enqueue(
            "/v1/ratings",
            RatingsRequest(
                Rounds.name(round),
                items.filter { it.status != RatingStatus.PENDING }.map {
                    RatingRow(
                        videoId = it.videoId,
                        position = it.position,
                        status = it.status,
                        wantsToStop = it.wantsToStop,
                        watchedSeconds = it.watchedSeconds,
                        errorCode = it.errorCode,
                    )
                },
            ),
        )
        outbox.flush()
        advance(StudyEvent.RATING_DONE)
    }

    // --- Valuations

    suspend fun answerValuation(round: Int, good: String, minAcceptPrice: Double?) {
        val state = state()
        val position = Goods.orderFor(state.samplingSeed).indexOf(good)
        dao.upsertValuation(ValuationAnswer(round, good, position, minAcceptPrice))
    }

    suspend fun submitValuations(round: Int, filterWtp: Double?) {
        enqueue(
            "/v1/valuations",
            ValuationsRequest(
                round = Rounds.name(round),
                valuations = dao.valuations(round).map {
                    ValuationRow(it.good, it.position, it.minAcceptPrice)
                },
                filterWtp = filterWtp,
            ),
        )
        outbox.flush()
        advance(StudyEvent.VALUATION_DONE)
    }

    // --- Randomization and task

    suspend fun assign() {
        val state = state()
        val config = configOf(state)
        // The server needs the ratings first, because it reads h from them.
        if (!outbox.flush()) throw IOException("Uploads are still waiting")
        val result = api.assign(checkNotNull(state.token))
        when {
            !result.eligible -> advance(StudyEvent.FOUND_INELIGIBLE)
            result.taskVideoIds.isEmpty() -> {
                startTracking(config)
                advance(StudyEvent.ASSIGNED_NO_TASK)
            }
            else -> {
                dao.setHideCreator(result.hideCreator)
                dao.insertTaskItems(
                    result.taskVideoIds.mapIndexed { i, id ->
                        TaskItem(id, i, DeepLinks.build(config.deepLinkTemplate, id))
                    },
                )
                advance(StudyEvent.ASSIGNED_WITH_TASK)
            }
        }
    }

    suspend fun recordHandoff(videoId: String, packageName: String) =
        dao.setHandoff(videoId, clock(), packageName)

    /** Called whenever the app comes to the front, which is how a return from TikTok is seen. */
    suspend fun onAppResumed() {
        if (dao.state()?.phase == Phase.TASK) dao.setReturned(clock())
    }

    suspend fun recordSelfReport(videoId: String, report: String) {
        dao.setSelfReport(videoId, report)
        val items = dao.taskItems()
        if (items.all { it.selfReport != null }) {
            val state = state()
            val config = configOf(state)
            uploadTaskLog()
            outbox.flush()
            startTracking(config)
            scheduler.scheduleComplianceRead(config.complianceReadDelaySeconds)
            advance(StudyEvent.TASK_DONE)
        }
    }

    private suspend fun startTracking(config: StudyConfig) {
        val now = clock()
        dao.setTreatedAt(now)
        scheduler.scheduleSyncAfter(config.trackingDays * config.daySeconds)
    }

    private suspend fun uploadTaskLog() {
        enqueue(
            "/v1/task-log",
            TaskLogRequest(
                dao.taskItems().map {
                    TaskLogRow(
                        videoId = it.videoId,
                        position = it.position,
                        handoffAtMillis = it.handoffAtMillis,
                        returnedAtMillis = it.returnedAtMillis,
                        handoffPackage = it.handoffPackage,
                        selfReport = it.selfReport,
                        foregroundSeconds = it.foregroundSeconds,
                    )
                },
            ),
        )
    }

    /**
     * Compliance proxy: time in the target app between each handoff and return. Runs after a
     * delay, because Android truncates the last few minutes of the event log.
     */
    suspend fun readCompliance() {
        val config = configOf(state())
        if (!usageReader.hasAccess()) return
        val items = dao.taskItems().filter { it.handoffAtMillis != null && it.returnedAtMillis != null }
        if (items.isEmpty()) return

        val start = items.minOf { checkNotNull(it.handoffAtMillis) } - 60_000
        val end = items.maxOf { checkNotNull(it.returnedAtMillis) } + 60_000
        val packages = config.handoffPackages.toSet()
        val sessions = withContext(Dispatchers.IO) {
            SessionBuilder.build(usageReader.events(start, end, packages).events, packages).closed
        }
        for (item in items) {
            val millis = SessionBuilder.foregroundMillis(
                sessions,
                packages,
                checkNotNull(item.handoffAtMillis),
                checkNotNull(item.returnedAtMillis),
            )
            dao.setForegroundSeconds(item.videoId, millis / 1000.0)
        }
        uploadTaskLog()
        outbox.flush()
    }

    // --- Tracking

    private fun bucketer(state: StudyState, config: StudyConfig): DayBucketer =
        if (config.isCompressedClock) {
            CompressedBucketer(checkNotNull(state.enrolledAtMillis), config.daySeconds * 1000)
        } else {
            LocalDateBucketer(ZoneId.systemDefault())
        }

    /** Stores finished sessions and queues daily totals. Safe to run at any time. */
    suspend fun syncUsage(): Unit = usageMutex.withLock {
        val state = dao.state() ?: return@withLock
        if (state.participantId == null) return@withLock
        val config = configOf(state)
        val tracked = config.trackedPackages.toSet()

        if (!usageReader.hasAccess()) {
            enqueue("/v1/usage", UsageRequest(emptyList(), null, null, false))
            return@withLock
        }
        val now = clock()
        val firstRun = state.usageCursorMillis == null
        val start = state.usageCursorMillis ?: (now - BACKFILL_DAYS * DAY_MILLIS)

        val request = withContext(Dispatchers.IO) {
            val events = usageReader.events(start, now, tracked)
            val built = SessionBuilder.build(events.events, tracked)

            // Sessions near "now" may be incomplete, so they wait for the next sync.
            val safeUntil = now - config.usageSafetyMarginSeconds * 1000
            val finished = built.closed.filter { it.endMillis <= safeUntil }
            dao.upsertSessions(
                finished.map { UsageSessionEntity(it.packageName, it.startMillis, it.endMillis) },
            )

            val unfinishedStarts = built.open.values +
                built.closed.filter { it.endMillis > safeUntil }.map { it.startMillis }
            dao.setUsageCursor(minOf(safeUntil, unfinishedStarts.minOrNull() ?: safeUntil))

            val sessions = dao.allSessions().map { Session(it.packageName, it.startMillis, it.endMillis) }
            val eventRows = UsageAggregator.aggregate(sessions, bucketer(state, config)).map {
                UsageRow(it.day, it.packageName, it.foregroundSeconds, it.sessions, SOURCE_EVENTS)
            }
            val daily = usageReader.dailyStats(now - BACKFILL_DAYS * DAY_MILLIS, now, tracked)
            val dailyRows = daily.rows.map {
                UsageRow(it.day, it.packageName, it.foregroundSeconds, null, SOURCE_DAILY)
            }
            UsageRequest(
                rows = eventRows + dailyRows,
                // Reported once, at install: this is how much pre-study history the phone has.
                earliestEventDay = if (firstRun) dayOf(events.earliestAnyEventMillis) else null,
                earliestDailyStatsDay = if (firstRun) dayOf(daily.earliestBucketMillis) else null,
                usageAccessGranted = true,
            )
        }
        enqueue("/v1/usage", request)
    }

    private fun dayOf(millis: Long?): String? =
        millis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }

    /** Run by background work. Returns false if uploads should be retried. */
    suspend fun backgroundSync(): Boolean {
        val state = dao.state() ?: return true
        if (state.participantId == null || state.phase == Phase.DONE) return true
        syncUsage()
        checkTrackingElapsed()
        return outbox.flush()
    }

    suspend fun checkTrackingElapsed() {
        val state = dao.state() ?: return
        if (state.phase != Phase.TRACKING) return
        val config = configOf(state)
        val treatedAt = state.treatedAtMillis ?: return
        if (StudyClock.isTrackingElapsed(clock(), treatedAt, config.trackingDays, config.daySeconds)) {
            advance(StudyEvent.TRACKING_ELAPSED)
            Notifier.post(
                context,
                "Time for the last part of the study",
                "Open Feed Study to request your TikTok data again.",
            )
        }
    }

    // --- Completion

    suspend fun finish() {
        val state = state()
        syncUsage()
        if (!outbox.flush()) throw IOException("Uploads are still waiting")
        val code = api.complete(checkNotNull(state.token)).completionCode
        dao.setCompletionCode(code)
        if (state.phase == Phase.FINISHING) {
            advance(StudyEvent.COMPLETED)
            scheduler.cancelAll()
        }
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        const val BACKFILL_DAYS = 30L
        const val RETAIN_DAYS = 120L
        const val SOURCE_EVENTS = "events"
        const val SOURCE_DAILY = "daily_stats"
    }
}
