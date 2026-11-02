package io.github.rafaeljjd.feedstudy.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.rafaeljjd.feedstudy.FeedStudyApp
import io.github.rafaeljjd.feedstudy.MainActivity
import java.util.concurrent.TimeUnit

class Scheduler(context: Context) {
    private val work = WorkManager.getInstance(context)

    fun schedulePeriodicSync(intervalMinutes: Long) {
        work.enqueueUniquePeriodicWork(
            PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES).build(),
        )
    }

    /** One extra sync, timed to the end of tracking so the prompt is not late. */
    fun scheduleSyncAfter(delaySeconds: Long) {
        work.enqueueUniqueWork(
            TRACKING_END_SYNC,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun scheduleExportReminder(delaySeconds: Long) {
        work.enqueueUniqueWork(
            EXPORT_REMINDER,
            // The worker schedules its own successor, which REPLACE would cancel mid-run.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun cancelExportReminder() {
        work.cancelUniqueWork(EXPORT_REMINDER)
    }

    fun scheduleComplianceRead(delaySeconds: Long) {
        work.enqueueUniqueWork(
            COMPLIANCE_READ,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ComplianceWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun cancelAll() {
        listOf(PERIODIC_SYNC, TRACKING_END_SYNC, EXPORT_REMINDER, COMPLIANCE_READ)
            .forEach(work::cancelUniqueWork)
    }

    private companion object {
        const val PERIODIC_SYNC = "periodic_sync"
        const val TRACKING_END_SYNC = "tracking_end_sync"
        const val EXPORT_REMINDER = "export_reminder"
        const val COMPLIANCE_READ = "compliance_read"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as FeedStudyApp).repository
        return if (repository.backgroundSync()) Result.success() else Result.retry()
    }
}

/**
 * TikTok gives the app no signal that an export is ready, so this reminds on a schedule. It
 * chains one-time work because periodic work has a 15-minute floor that a compressed test
 * clock cannot use.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as FeedStudyApp).repository.sendExportReminder()
        return Result.success()
    }
}

class ComplianceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as FeedStudyApp).repository.readCompliance()
        return Result.success()
    }
}

object Notifier {
    private const val CHANNEL = "study"

    fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Study reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            title.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
