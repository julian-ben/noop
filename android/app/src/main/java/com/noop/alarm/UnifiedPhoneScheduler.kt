package com.noop.alarm

import android.content.Context
import java.time.ZoneId

/**
 * Durable phone-side scheduler for unified alarms.
 *
 * AlarmManager has no query API, so the ids we register must be persisted. That lets a later process
 * cancel stale one-shots after an edit/delete/disable, and lets boot rebuild the exact desired set.
 */
class UnifiedPhoneScheduler(
    context: Context,
    private val store: UnifiedAlarmStore,
) : PhoneScheduler {

    private val appContext = context.applicationContext

    override fun reconcile(desired: List<PhoneFire>) {
        val previousIds = store.scheduledPhoneAlarmIds()
        val desiredIds = desired.map { it.alarmId }.toSet()

        for (drop in previousIds - desiredIds) {
            SmartAlarmScheduler.cancelOneShot(appContext, drop)
        }
        for (fire in desired) {
            SmartAlarmScheduler.scheduleOneShot(
                appContext,
                alarmId = fire.alarmId,
                fireAtMs = fire.fireAtEpochMs,
                deadlineAtMs = fire.fireAtEpochMs,
            )
        }
        store.setScheduledPhoneAlarmIds(desiredIds)
        mirrorSmartWakeHead(desired)
    }

    override fun cancel(alarmId: String) {
        SmartAlarmScheduler.cancelOneShot(appContext, alarmId)
        store.setScheduledPhoneAlarmIds(store.scheduledPhoneAlarmIds() - alarmId)
    }

    private fun mirrorSmartWakeHead(desired: List<PhoneFire>) {
        val smartHead = desired
            .filter { it.smartWake && !it.isStrapPhoneBackup }
            .minByOrNull { it.fireAtEpochMs }
        val legacy = SmartAlarmStore.from(appContext)
        if (smartHead != null) {
            legacy.enabled = true
            legacy.scheduledAlarmId = smartHead.alarmId
            legacy.scheduledDeadlineMs = smartHead.fireAtEpochMs
            legacy.scheduledWindowStartMs = smartHead.windowStartEpochMs
        } else {
            legacy.enabled = false
            legacy.scheduledAlarmId = null
            legacy.scheduledDeadlineMs = 0L
            legacy.scheduledWindowStartMs = 0L
        }
    }

    companion object {
        fun recompute(
            context: Context,
            store: UnifiedAlarmStore = UnifiedAlarmStore.from(context),
            nowEpochMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ) {
            val schedule = UnifiedAlarmResolver.resolveSchedule(store.alarms.value, nowEpochMs, zone)
            UnifiedPhoneScheduler(context, store).reconcile(schedule.phoneAlarms)
        }
    }
}
