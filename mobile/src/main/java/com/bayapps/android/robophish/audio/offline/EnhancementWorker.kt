package com.bayapps.android.robophish.audio.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ui.SavedAudioActivity
import timber.log.Timber
import java.io.File
import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** WorkManager restarts an interrupted generation from its original; partial output is never played. */
class EnhancementWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val repository by lazy { EnhancementRepository.get(applicationContext) }

    override suspend fun doWork(): Result {
        val key = inputData.getString("trackKey") ?: return Result.failure()
        val generation = inputData.getString("generation") ?: return Result.failure()
        val entry = repository.store.find(key)?.takeIf { it.generation == generation && it.isActive } ?: return Result.success()
        return try {
            renderMutex.withLock { render(entry) }
        } catch (cancelled: CancellationException) {
            repository.interrupted(entry)
            throw cancelled
        }
    }

    private suspend fun render(entry: EnhancementEntry): Result = withContext(Dispatchers.IO) {
        var published = false
        val job = coroutineContext[Job]
        val oldPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        try {
            checkCancelled(entry, job)
            setForeground(foreground(entry, 0))
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            repository.part(entry).delete()
            repository.output(entry).delete()
            val source = File(URI(entry.sourceUri))
            check(source.length() == entry.sourceSize && source.lastModified() == entry.sourceModified) {
                "Original recording changed. Download it again before processing."
            }
            var previousPercent = -1
            var previousTime = 0L
            repository.progress(entry, EnhancementStatus.ANALYZING, 0)
            val result = OfflineAudioRenderer().render(source, repository.part(entry), { checkCancelled(entry, job) }) { status, percent ->
                val now = SystemClock.elapsedRealtime()
                if (percent != previousPercent && now - previousTime >= 500) {
                    repository.progress(entry, status, percent)
                    setForegroundAsync(foreground(entry, percent))
                    previousPercent = percent; previousTime = now
                }
            }
            checkCancelled(entry, job)
            published = if (result.changed) repository.publish(entry, result.sourceSha256, result.outputBytes, result.summary)
                else repository.publishUnchanged(entry, result.sourceSha256, result.summary)
            Result.success()
        } catch (cancelled: CancellationException) {
            repository.interrupted(entry)
            throw cancelled
        } catch (_: InterruptedException) {
            repository.interrupted(entry)
            Thread.currentThread().interrupt()
            Result.retry()
        } catch (error: Exception) {
            Timber.w(error, "Unable to create saved audio version")
            repository.failed(entry, userMessage(error))
            Result.failure()
        } finally {
            runCatching { repository.part(entry).delete() }
            if (!published) runCatching { repository.output(entry).delete() }
            runCatching { android.os.Process.setThreadPriority(oldPriority) }
        }
    }

    private fun checkCancelled(entry: EnhancementEntry, job: Job?) {
        if (isStopped || job?.isActive == false || Thread.currentThread().isInterrupted || !repository.current(entry)) throw CancellationException()
    }

    private fun foreground(entry: EnhancementEntry, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Saved audio processing", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, SavedAudioActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (percent <= 40) "Analyzing recording" else "Saving processed audio")
            .setContentText(entry.title)
            .setContentIntent(intent)
            .setProgress(100, percent, percent == 0)
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).build()
        val notificationId = 10000 + (entry.generation.hashCode() and 0x0fffffff)
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, type)
            else ForegroundInfo(notificationId, notification)
    }

    private fun userMessage(error: Exception): String {
        val detail = error.message.orEmpty().lowercase()
        return when {
            "space" in detail || "storage" in detail -> "Not enough free storage for a lossless version. Free space and retry."
            "original" in detail -> "The original download is missing or changed. Download it again and retry."
            "wav size" in detail -> "This recording is too large for a WAV file. The original is still available."
            "mono and stereo" in detail -> "This format is unsupported. Processing supports mono or stereo recordings at 22.05–96 kHz."
            "duration" in detail || "incomplete" in detail || "trim metadata" in detail -> "The recording appears incomplete. Download the original again and retry."
            else -> "Processing could not finish. The original is unchanged. You can retry or remove this job."
        }
    }

    companion object {
        private const val CHANNEL = "SavedAudioProcessing"
        private val renderMutex = Mutex()
    }
}
