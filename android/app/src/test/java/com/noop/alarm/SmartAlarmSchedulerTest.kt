package com.noop.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [SmartAlarmScheduler.scheduleOneShot].
 */
class SmartAlarmSchedulerTest {

    private fun stubContext(alarmManager: AlarmManager): Context {
        val ctx = mock<Context>()
        val launchIntent = mock<Intent>()
        whenever(launchIntent.addFlags(any())).thenReturn(launchIntent)
        val packageManager = mock<android.content.pm.PackageManager>()
        whenever(packageManager.getLaunchIntentForPackage("com.noop")).thenReturn(launchIntent)
        whenever(ctx.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        whenever(ctx.packageName).thenReturn("com.noop")
        whenever(ctx.applicationContext).thenReturn(ctx)
        whenever(ctx.packageManager).thenReturn(packageManager)
        return ctx
    }

    /** Run block with PendingIntent factory calls mocked to return non-null stubs. */
    private fun withMockedPendingIntent(block: () -> Unit) {
        val fakeBroadcast = mock<PendingIntent>()
        val fakeActivity = mock<PendingIntent>()
        Mockito.mockStatic(PendingIntent::class.java).use { piStatic ->
            piStatic.`when`<PendingIntent> {
                PendingIntent.getBroadcast(any(), any(), any(), any())
            }.thenReturn(fakeBroadcast)
            piStatic.`when`<PendingIntent> {
                PendingIntent.getActivity(any(), any(), any(), any())
            }.thenReturn(fakeActivity)
            block()
        }
    }

    @Test fun schedulerUsesAlarmClockForOneShotWakeAlarms() {
        val am = mock<AlarmManager>()
        val ctx = stubContext(am)

        withMockedPendingIntent {
            SmartAlarmScheduler.scheduleOneShot(ctx, alarmId = "test-id", fireAtMs = 9_000_000_000L)
            verify(am).cancel(any<PendingIntent>())
            verify(am).setAlarmClock(any(), any())
        }
    }
}
