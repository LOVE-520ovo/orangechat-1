/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WorkManager-based fallback for proactive message scheduling.
 * More reliable than AlarmManager on devices with aggressive battery optimization.
 */
class ProactiveMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProactiveMessageWorker"
        private const val UNIQUE_WORK_NAME = "proactive_message_work"

        fun ensureWatchdog(context: Context, setting: me.rerere.rikkahub.data.datastore.ProactiveMessageSetting) {
            if (!setting.enabled) {
                cancel(context)
                return
            }
            val workRequest = PeriodicWorkRequestBuilder<ProactiveMessageWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
            Log.d(TAG, "Watchdog ensured (1h periodic)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled WorkManager proactive message")
        }

        /**
         * Check if exact alarm permission is granted (Android 12+)
         */
        fun canScheduleExactAlarms(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return true
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }

        /**
         * Check if app is ignoring battery optimizations
         */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Watchdog tick")
        val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
        val settings = settingsStore.settingsFlow.first()
        val proactiveSetting = settings.proactiveMessageSetting
        if (!proactiveSetting.enabled) {
            Log.d(TAG, "proactive message disabled, skipping watchdog")
            return Result.success()
        }
        val prefs = applicationContext.getSharedPreferences("proactive_message_prefs", Context.MODE_PRIVATE)
        val next = prefs.getLong("next_trigger_time", 0L)
        val now = System.currentTimeMillis()
        if (next <= 0L || next < now - 600000L) {
            Log.w(TAG, "alarm missing or overdue, rescheduling")
            try {
                ProactiveMessageService.scheduleNext(applicationContext, proactiveSetting)
            } catch (e: Exception) {
                Log.e(TAG, "reschedule failed", e)
            }
        } else {
            Log.d(TAG, "alarm healthy, next in " + ((next - now) / 60000L) + " min")
        }
        return Result.success()
    }
}
