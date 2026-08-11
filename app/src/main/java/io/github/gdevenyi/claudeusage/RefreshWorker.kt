package io.github.gdevenyi.claudeusage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Fetches usage, then pushes the result to both surfaces. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        try {
            Usage.fetchAndCache(applicationContext)
            Usage.cached(applicationContext)?.let { History.append(applicationContext, it) }
        } catch (e: Exception) {
            // keep showing cached data; surfaces render staleness/auth state themselves
            Log.w("ClaudeUsage", "refresh failed", e)
        }
        UsageWidget().updateAll(applicationContext)
        Notif.update(applicationContext)
        Notif.armReset(applicationContext, Usage.cached(applicationContext)?.session?.resetsAt)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(
                Store(ctx).intervalMin.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun refreshNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "refresh-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RefreshWorker>().build(),
            )
        }
    }
}

/** Notification tap target: refresh in place. */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RefreshWorker.refreshNow(context)
    }
}
