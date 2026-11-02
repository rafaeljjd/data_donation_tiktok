package io.github.rafaeljjd.feedstudy.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import io.github.rafaeljjd.feedstudy.study.Phase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "study_state")
data class StudyState(
    @PrimaryKey val id: Int = 1,
    val phase: Phase = Phase.ENROLL,
    val participantId: String? = null,
    val token: String? = null,
    val configJson: String? = null,
    val enrolledAtMillis: Long? = null,
    val samplingSeed: Long = 0,
    val treatedAtMillis: Long? = null,
    val usageCursorMillis: Long? = null,
    val hideCreator: Boolean = false,
    val completionCode: String? = null,
)

/** Parsed export rows. These stay on the phone: only video IDs and counts are uploaded. */
@Entity(tableName = "export_records", indices = [Index("round"), Index("videoId")])
data class ExportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val round: Int,
    val kind: String,
    val videoId: String,
    val url: String,
    val watchedAtMillis: Long,
)

object RatingStatus {
    const val PENDING = "pending"
    const val RATED = "rated"
    const val UNAVAILABLE = "unavailable"
}

@Entity(tableName = "rating_items", primaryKeys = ["round", "videoId"])
data class RatingItem(
    val round: Int,
    val videoId: String,
    val position: Int,
    val status: String = RatingStatus.PENDING,
    val wantsToStop: Boolean? = null,
    val watchedSeconds: Double = 0.0,
    val errorCode: String? = null,
)

@Entity(tableName = "valuation_answers", primaryKeys = ["round", "good"])
data class ValuationAnswer(
    val round: Int,
    val good: String,
    val position: Int,
    val minAcceptPrice: Double?,
)

@Entity(tableName = "task_items")
data class TaskItem(
    @PrimaryKey val videoId: String,
    val position: Int,
    val url: String,
    val handoffAtMillis: Long? = null,
    val returnedAtMillis: Long? = null,
    val handoffPackage: String? = null,
    val selfReport: String? = null,
    val foregroundSeconds: Double? = null,
)

@Entity(tableName = "usage_sessions", primaryKeys = ["packageName", "startMillis"])
data class UsageSessionEntity(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
)

/** Uploads wait here until the server has them, so being offline loses nothing. */
@Entity(tableName = "outbox")
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val body: String,
    val failedCode: Int? = null,
)

@Dao
interface StudyDao {
    // State. Updates touch single columns, so the UI and background sync cannot overwrite
    // each other's changes.
    @Query("SELECT * FROM study_state WHERE id = 1")
    fun stateFlow(): Flow<StudyState?>

    @Query("SELECT * FROM study_state WHERE id = 1")
    suspend fun state(): StudyState?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertState(state: StudyState)

    @Query("UPDATE study_state SET phase = :to WHERE id = 1 AND phase = :from")
    suspend fun movePhase(from: Phase, to: Phase): Int

    @Query(
        "UPDATE study_state SET participantId = :participantId, token = :token, " +
            "configJson = :configJson, enrolledAtMillis = :enrolledAt, samplingSeed = :seed " +
            "WHERE id = 1",
    )
    suspend fun saveEnrollment(
        participantId: String,
        token: String,
        configJson: String,
        enrolledAt: Long,
        seed: Long,
    )

    @Query("UPDATE study_state SET treatedAtMillis = :millis WHERE id = 1")
    suspend fun setTreatedAt(millis: Long)

    @Query("UPDATE study_state SET usageCursorMillis = :millis WHERE id = 1")
    suspend fun setUsageCursor(millis: Long)

    @Query("UPDATE study_state SET hideCreator = :hide WHERE id = 1")
    suspend fun setHideCreator(hide: Boolean)

    @Query("UPDATE study_state SET completionCode = :code WHERE id = 1")
    suspend fun setCompletionCode(code: String)

    // Export records
    @Insert
    suspend fun insertRecords(records: List<ExportRecordEntity>)

    @Query("DELETE FROM export_records WHERE round = :round")
    suspend fun deleteRecords(round: Int)

    @Query("SELECT * FROM export_records")
    suspend fun allRecords(): List<ExportRecordEntity>

    // Ratings
    @Query("DELETE FROM rating_items WHERE round = :round")
    suspend fun deleteRatings(round: Int)

    @Insert
    suspend fun insertRatings(items: List<RatingItem>)

    @Query("SELECT * FROM rating_items WHERE round = :round ORDER BY position")
    fun ratingsFlow(round: Int): Flow<List<RatingItem>>

    @Query("SELECT * FROM rating_items WHERE round = :round ORDER BY position")
    suspend fun ratings(round: Int): List<RatingItem>

    @Query(
        "UPDATE rating_items SET status = 'rated', wantsToStop = :wantsToStop, " +
            "watchedSeconds = :watchedSeconds WHERE round = :round AND videoId = :videoId " +
            "AND status = 'pending'",
    )
    suspend fun setRated(round: Int, videoId: String, wantsToStop: Boolean, watchedSeconds: Double)

    @Query(
        "UPDATE rating_items SET status = 'unavailable', errorCode = :errorCode " +
            "WHERE round = :round AND videoId = :videoId AND status = 'pending'",
    )
    suspend fun setUnavailable(round: Int, videoId: String, errorCode: String)

    // Valuations
    @Upsert
    suspend fun upsertValuation(answer: ValuationAnswer)

    @Query("SELECT * FROM valuation_answers WHERE round = :round ORDER BY position")
    fun valuationsFlow(round: Int): Flow<List<ValuationAnswer>>

    @Query("SELECT * FROM valuation_answers WHERE round = :round ORDER BY position")
    suspend fun valuations(round: Int): List<ValuationAnswer>

    // Task
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskItems(items: List<TaskItem>)

    @Query("SELECT * FROM task_items ORDER BY position")
    fun taskItemsFlow(): Flow<List<TaskItem>>

    @Query("SELECT * FROM task_items ORDER BY position")
    suspend fun taskItems(): List<TaskItem>

    @Query(
        "UPDATE task_items SET handoffAtMillis = :at, handoffPackage = :pkg, " +
            "returnedAtMillis = NULL WHERE videoId = :videoId",
    )
    suspend fun setHandoff(videoId: String, at: Long, pkg: String)

    @Query(
        "UPDATE task_items SET returnedAtMillis = :at " +
            "WHERE handoffAtMillis IS NOT NULL AND returnedAtMillis IS NULL",
    )
    suspend fun setReturned(at: Long)

    @Query("UPDATE task_items SET selfReport = :report WHERE videoId = :videoId")
    suspend fun setSelfReport(videoId: String, report: String)

    @Query("UPDATE task_items SET foregroundSeconds = :seconds WHERE videoId = :videoId")
    suspend fun setForegroundSeconds(videoId: String, seconds: Double)

    // Usage
    @Upsert
    suspend fun upsertSessions(sessions: List<UsageSessionEntity>)

    @Query("SELECT * FROM usage_sessions")
    suspend fun allSessions(): List<UsageSessionEntity>

    // Outbox
    @Insert
    suspend fun enqueue(item: OutboxItem)

    @Query("SELECT * FROM outbox WHERE failedCode IS NULL ORDER BY id")
    suspend fun pendingOutbox(): List<OutboxItem>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteOutbox(id: Long)

    @Query("UPDATE outbox SET failedCode = :code WHERE id = :id")
    suspend fun markOutboxFailed(id: Long, code: Int)
}

@Database(
    entities = [
        StudyState::class,
        ExportRecordEntity::class,
        RatingItem::class,
        ValuationAnswer::class,
        TaskItem::class,
        UsageSessionEntity::class,
        OutboxItem::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun dao(): StudyDao

    companion object {
        fun create(context: Context): StudyDatabase =
            Room.databaseBuilder(context, StudyDatabase::class.java, "study.db").build()
    }
}
