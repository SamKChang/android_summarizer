package com.gist.app.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gist.app.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily periodic worker that deletes chat sessions older than the configured retention period (§8.1).
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionDao: SessionDao,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settingsFlow.first()
        val retentionDays = settings.retentionDays
        if (retentionDays > 0) {
            val cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
            sessionDao.deleteSessionsOlderThan(cutoffMillis)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "gist_history_retention_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
