package io.github.rafaeljjd.feedstudy.net

import io.github.rafaeljjd.feedstudy.data.OutboxItem
import io.github.rafaeljjd.feedstudy.data.StudyDao
import io.github.rafaeljjd.feedstudy.study.StudyConfig
import io.github.rafaeljjd.feedstudy.study.StudyJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// Everything the app can send. These types hold no links, titles, timestamps of viewing, or
// other export content, and the server rejects any field it does not list.

@Serializable
data class EnrollRequest(val prolificId: String, val appVersion: String, val androidSdk: Int)

@Serializable
data class EnrollResponse(val participantId: String, val token: String, val config: StudyConfig)

@Serializable
data class ConsentRequest(val consentVersion: String, val acceptedAtMillis: Long)

@Serializable
data class VideoRow(val videoId: String, val window: String, val watchCount: Int)

@Serializable
data class ExportSummary(
    val watchCount: Int,
    val likeCount: Int,
    val favoriteCount: Int,
    val droppedNoVideoId: Int,
    val droppedBadDate: Int,
    val ignoredOtherRecords: Int,
    val windowDays: Double? = null,
)

@Serializable
data class VideosRequest(val round: String, val summary: ExportSummary, val videos: List<VideoRow>)

@Serializable
data class RatingRow(
    val videoId: String,
    val position: Int,
    val status: String,
    val wantsToStop: Boolean?,
    val watchedSeconds: Double,
    val errorCode: String?,
)

@Serializable
data class RatingsRequest(val round: String, val ratings: List<RatingRow>)

@Serializable
data class ValuationRow(val good: String, val position: Int, val minAcceptPrice: Double?)

@Serializable
data class ValuationsRequest(
    val round: String,
    val valuations: List<ValuationRow>,
    val filterWtp: Double?,
)

@Serializable
data class AssignResponse(
    val eligible: Boolean,
    val taskVideoIds: List<String>,
    val hideCreator: Boolean,
)

@Serializable
data class TaskLogRow(
    val videoId: String,
    val position: Int,
    val handoffAtMillis: Long?,
    val returnedAtMillis: Long?,
    val handoffPackage: String?,
    val selfReport: String?,
    val foregroundSeconds: Double?,
)

@Serializable
data class TaskLogRequest(val items: List<TaskLogRow>)

@Serializable
data class UsageRow(
    val day: String,
    val `package`: String,
    val foregroundSeconds: Long,
    val sessions: Int?,
    val source: String,
)

@Serializable
data class UsageRequest(
    val rows: List<UsageRow>,
    val earliestEventDay: String?,
    val earliestDailyStatsDay: String?,
    val usageAccessGranted: Boolean,
)

@Serializable
data class CompleteResponse(val completionCode: String)

class HttpFailure(val code: Int, message: String) : IOException(message)

class ApiClient(private val baseUrl: String) {
    private fun connection(path: String, token: String?): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    suspend fun post(path: String, body: String?, token: String?): String =
        withContext(Dispatchers.IO) {
            val conn = connection(path, token)
            try {
                conn.requestMethod = "POST"
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val detail = conn.errorStream?.use { it.bufferedReader().readText() }.orEmpty()
                    throw HttpFailure(code, "HTTP $code on $path: ${detail.take(300)}")
                }
                conn.inputStream.use { it.bufferedReader().readText() }
            } finally {
                conn.disconnect()
            }
        }

    /** Passes the response body to [use] while the connection is open. */
    suspend fun <T> download(path: String, token: String?, use: (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val conn = connection(path, token)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw HttpFailure(code, "HTTP $code on $path")
                conn.inputStream.use(use)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun enroll(request: EnrollRequest): EnrollResponse = StudyJson.decodeFromString(
        post("/v1/enroll", StudyJson.encodeToString(request), null),
    )

    suspend fun assign(token: String): AssignResponse =
        StudyJson.decodeFromString(post("/v1/assign", null, token))

    suspend fun complete(token: String): CompleteResponse =
        StudyJson.decodeFromString(post("/v1/complete", null, token))
}

/** Delivers queued uploads in order. Each server route is an upsert, so retries are safe. */
class Outbox(private val dao: StudyDao, private val api: ApiClient) {
    private val mutex = Mutex()

    suspend fun enqueue(path: String, body: String) = dao.enqueue(OutboxItem(path = path, body = body))

    /** Returns false if delivery should be retried later. */
    suspend fun flush(): Boolean = mutex.withLock {
        val token = dao.state()?.token ?: return false
        for (item in dao.pendingOutbox()) {
            try {
                api.post(item.path, item.body, token)
                dao.deleteOutbox(item.id)
            } catch (e: HttpFailure) {
                if (e.code == 401 || e.code == 408 || e.code == 429 || e.code >= 500) return false
                // The server rejected this payload, and a retry would fail the same way. Keep
                // it for inspection and let the rest of the queue through.
                dao.markOutboxFailed(item.id, e.code)
            } catch (e: IOException) {
                return false
            }
        }
        true
    }
}
