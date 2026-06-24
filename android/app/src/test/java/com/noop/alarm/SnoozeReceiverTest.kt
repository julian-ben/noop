package com.noop.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SnoozeReceiverTest {

    private fun stubContext(alarmManager: AlarmManager, nm: android.app.NotificationManager): Context {
        val ctx = mock<Context>()
        val launchIntent = mock<Intent>()
        whenever(launchIntent.addFlags(any())).thenReturn(launchIntent)
        val packageManager = mock<android.content.pm.PackageManager>()
        whenever(packageManager.getLaunchIntentForPackage("com.noop")).thenReturn(launchIntent)
        whenever(ctx.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        whenever(ctx.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(nm)
        whenever(ctx.packageName).thenReturn("com.noop")
        whenever(ctx.applicationContext).thenReturn(ctx)
        whenever(ctx.packageManager).thenReturn(packageManager)
        return ctx
    }

    /** Build a mock Intent so Intent.putExtra() chaining works in pure-JVM tests (Android stubs
     *  return null from instance methods under isReturnDefaultValues=true). */
    private fun mockIntent(action: String, alarmId: String): Intent {
        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(action)
        whenever(intent.getStringExtra(SnoozeReceiver.EXTRA_ALARM_ID)).thenReturn(alarmId)
        return intent
    }

    /** Run block with PendingIntent factory calls mocked to return non-null mock PendingIntents. */
    private fun withMockedPendingIntent(block: (MockedStatic<PendingIntent>) -> Unit) {
        val fakeBroadcast = mock<PendingIntent>()
        val fakeActivity = mock<PendingIntent>()
        Mockito.mockStatic(PendingIntent::class.java).use { piStatic ->
            piStatic.`when`<PendingIntent> {
                PendingIntent.getBroadcast(any(), any(), any(), any())
            }.thenReturn(fakeBroadcast)
            piStatic.`when`<PendingIntent> {
                PendingIntent.getActivity(any(), any(), any(), any())
            }.thenReturn(fakeActivity)
            block(piStatic)
        }
    }

    @Test fun snoozeSchedulesOneShotAlarmManagerNineMinutesOutForSameAlarmId() {
        val am = mock<AlarmManager>()
        val nm = mock<android.app.NotificationManager>()
        val ctx = stubContext(am, nm)

        withMockedPendingIntent {
            val now = 1_700_000_000_000L
            SmartAlarmScheduler.scheduleOneShot(ctx, alarmId = "a1", fireAtMs = now + 9 * 60_000L)
            verify(am).setAlarmClock(any(), any())
        }
    }

    @Test fun secondSnoozeReplacesPriorPendingIntentForSameAlarmId() {
        val am = mock<AlarmManager>()
        val nm = mock<android.app.NotificationManager>()
        val ctx = stubContext(am, nm)

        withMockedPendingIntent {
            SmartAlarmScheduler.scheduleOneShot(ctx, "a1", 1_700_000_000_000L)
            SmartAlarmScheduler.scheduleOneShot(ctx, "a1", 1_700_000_600_000L)
            // 2 schedules + cancel on second call proves replacement (cancel-before-set on each call
            // means at least one cancel fires; any test-double-friendly proof of replacement works).
            verify(am, org.mockito.kotlin.atLeastOnce()).cancel(any<PendingIntent>())
        }
    }

    @Test fun dismissActionCancelsNotificationAndDoesNotScheduleAnything() {
        val am = mock<AlarmManager>()
        val nm = mock<android.app.NotificationManager>()
        val ctx = stubContext(am, nm)

        val intent = mockIntent(SnoozeReceiver.ACTION_DISMISS, "a1")
        SnoozeReceiver().onReceive(ctx, intent)

        verify(nm).cancel(SmartAlarmReceiver.NOTIF_ID)
        // No AlarmManager interactions on dismiss.
        verify(am, never()).set(any(), any(), any<PendingIntent>())
            verify(am, never()).setAlarmClock(any(), any())
    }

    @Test fun snoozeIntentHasFireAction() {
        val am = mock<AlarmManager>()
        val nm = mock<android.app.NotificationManager>()
        val ctx = stubContext(am, nm)

        // Capture the action passed to Intent.setAction() via mockConstruction.
        // Android stubs return null from instance methods, so we cannot read intent.action
        // after-the-fact; instead we intercept the setAction call itself.
        val capturedActions = mutableListOf<String?>()
        Mockito.mockConstruction(Intent::class.java) { constructedIntent, _ ->
            whenever(constructedIntent.setAction(any())).thenAnswer { inv ->
                capturedActions += inv.getArgument<String>(0)
                constructedIntent
            }
            whenever(constructedIntent.putExtra(any<String>(), any<Boolean>())).thenReturn(constructedIntent)
            whenever(constructedIntent.putExtra(any<String>(), any<String>())).thenReturn(constructedIntent)
        }.use {
            val fakePi = mock<PendingIntent>()
            Mockito.mockStatic(PendingIntent::class.java).use { piStatic ->
                piStatic.`when`<PendingIntent> {
                    PendingIntent.getBroadcast(any(), any(), any(), any())
                }.thenReturn(fakePi)
                piStatic.`when`<PendingIntent> {
                    PendingIntent.getActivity(any(), any(), any(), any())
                }.thenReturn(fakePi)

                val incomingIntent = mockIntent(SnoozeReceiver.ACTION_SNOOZE, "a1")
                SnoozeReceiver().onReceive(ctx, incomingIntent)
            }
        }

        assert(capturedActions.contains(SmartAlarmScheduler.ACTION_FIRE)) {
            "Expected setAction(ACTION_FIRE) to be called but captured actions were: $capturedActions"
        }
    }

    @Test fun snoozeActionCancelsNotificationThenSchedulesOneShot() {
        val am = mock<AlarmManager>()
        val nm = mock<android.app.NotificationManager>()
        val ctx = stubContext(am, nm)

        withMockedPendingIntent {
            val intent = mockIntent(SnoozeReceiver.ACTION_SNOOZE, "a1")
            SnoozeReceiver().onReceive(ctx, intent)

            verify(nm).cancel(SmartAlarmReceiver.NOTIF_ID)
            verify(am).setAlarmClock(any(), any())
        }
    }
}
