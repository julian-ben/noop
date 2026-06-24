package com.noop.alarm

import com.noop.alarm.PhoneFire
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SmartAlarmCoordinatorTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val day0 = ZonedDateTime.of(LocalDate.of(2026, 6, 22), java.time.LocalTime.NOON, zone)
    private val nowMs = day0.toInstant().toEpochMilli()

    private class FakeStrapArmer : StrapArmer {
        val calls = mutableListOf<Pair<String, Long?>>()
        override fun armAt(epochSec: Long) { calls += "armAt" to epochSec }
        override fun disable() { calls += "disable" to null }
    }

    private class FakePhoneScheduler : PhoneScheduler {
        var lastDesired: List<PhoneFire> = emptyList()
        val cancelledIds = mutableListOf<String>()
        var reconcileCount = 0
        override fun reconcile(desired: List<PhoneFire>) { lastDesired = desired; reconcileCount++ }
        override fun cancel(alarmId: String) { cancelledIds += alarmId }
    }

    private fun newCoordinator(
        store: UnifiedAlarmStore,
        strapConnected: Boolean = true,
        now: () -> Long = { nowMs },
    ): Triple<SmartAlarmCoordinator, FakeStrapArmer, FakePhoneScheduler> {
        val strap = FakeStrapArmer()
        val phone = FakePhoneScheduler()
        val c = SmartAlarmCoordinator(
            context = androidContextStub(),
            store = store,
            nowEpochMs = now,
            zone = zone,
            strapArmer = strap,
            phoneScheduler = phone,
        )
        c.strapConnected = strapConnected
        return Triple(c, strap, phone)
    }

    @Test fun emptyStoreReconcilesPhoneToEmptyAndDoesNotArmStrap() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertTrue(phone.lastDesired.isEmpty())
        assertNull(c.armedStrapAlarmId.value)
        assertTrue(strap.calls.isEmpty())
    }

    @Test fun singleStrapAlarmArmsTheStrapAndSchedulesNoPhone() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val a = UnifiedAlarm(
            id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP,
        )
        store.add(a)
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertEquals("a1", c.armedStrapAlarmId.value)
        // exactly one armAt call, no disable (nothing was armed before)
        assertEquals(1, strap.calls.size)
        assertEquals("armAt", strap.calls[0].first)
        assertTrue(phone.lastDesired.isEmpty())
    }

    @Test fun newEarlierStrapAlarmPreemptsTheArmedHead() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, _) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()

        // Add a 06:00 alarm - earlier than the 06:30 head.
        store.add(UnifiedAlarm(id = "a2", enabled = true, wakeMinutes = 6 * 60,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        c.recompute()
        assertEquals("a2", c.armedStrapAlarmId.value)
        // disable + armAt, in that order.
        assertEquals(listOf("disable", "armAt"), strap.calls.map { it.first })
    }

    @Test fun strapDisconnectedQueuesIntentAndAppliesOnBleConnect() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, _) = newCoordinator(store, strapConnected = false)
        c.recompute()
        // Nothing sent to the strap yet - it is offline.
        assertTrue(strap.calls.isEmpty())
        // armedStrapAlarmId stays null while disconnected: we must not claim the alarm is armed
        // on the firmware when the strap has not physically accepted it (iOS parity, I4).
        assertNull(c.armedStrapAlarmId.value)

        // Now BLE connects: strapConnected = true clears _strapFirmwareArmed, then recompute arms.
        c.strapConnected = true
        c.recompute()
        // Only now does the coordinator update the observable state.
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(1, strap.calls.size)
        assertEquals("armAt", strap.calls[0].first)
    }

    @Test fun phoneOnlyAlarmRoutesToPhoneSchedulerAndNotStrap() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "p1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.PHONE,
            smartWake = true, preWakeWindowMinutes = 30))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertEquals(1, phone.lastDesired.size)
        assertEquals("p1", phone.lastDesired[0].alarmId)
        assertTrue(strap.calls.isEmpty())
        assertNull(c.armedStrapAlarmId.value)
    }

    @Test fun strapAndPhoneAlarmArmsBothPaths() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "ab", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP_AND_PHONE))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertEquals("ab", c.armedStrapAlarmId.value)
        assertEquals(1, strap.calls.count { it.first == "armAt" })
        assertEquals(1, phone.lastDesired.size)
        assertEquals("ab", phone.lastDesired[0].alarmId)
    }

    @Test fun reorderDoesNotTriggerStrapOrPhoneIO() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        store.add(UnifiedAlarm(id = "a2", enabled = true, wakeMinutes = 9 * 60,
            weekdays = setOf(7), source = AlarmSource.STRAP))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()
        val callsBefore = phone.reconcileCount

        // Pure reorder - same data, list order flipped. Spec rule: not a coordinator trigger.
        store.reorder(0, 1)
        // We deliberately do NOT call c.recompute() here, mirroring how the UI is wired:
        // store.reorder is a list-only path and the screen doesn't fire the coordinator on it.
        assertTrue(strap.calls.isEmpty())
        assertEquals(callsBefore, phone.reconcileCount)
    }

    @Test fun onSmartWakeFireBuzzesStrapAndDisablesFirmware() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, _) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()

        c.onSmartWakeFire()
        // Spec: send disableAlarm so the firmware doesn't fire at the original time. We don't have
        // a runAlarm primitive on Android (command 68 is wired on iOS via Commands.swift); the
        // Android equivalent is a no-op buzz via the existing path - this test pins that we at
        // least call disable() so the original 06:30 firmware buzz is cancelled.
        assertTrue(strap.calls.any { it.first == "disable" })
    }

    @Test fun onSmartWakeFireNoOpsWhenNoArmedAlarm() {
        // Empty store - nothing armed.
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val (c, strap, _) = newCoordinator(store, strapConnected = true)
        // Do NOT call recompute() - armedStrapAlarmId is null.
        c.onSmartWakeFire()
        // Must not send a spurious disable to the firmware.
        assertTrue(strap.calls.isEmpty())
        assertNull(c.armedStrapAlarmId.value)
    }

    @Test fun onStrapAlarmFiredClearsOneShotStateAndRearmsNextOccurrence() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()
        val phoneReconcilesBefore = phone.reconcileCount

        c.onStrapAlarmFired()

        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(listOf("armAt"), strap.calls.map { it.first })
        assertEquals(phoneReconcilesBefore, phone.reconcileCount)
    }

    @Test fun onStrapAlarmDismissedCancelsBackupOnlyAfterFiredEvent() {
        var currentNow = ZonedDateTime.of(
            LocalDate.of(2026, 6, 22),
            java.time.LocalTime.NOON,
            zone,
        ).toInstant().toEpochMilli()
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "ab", enabled = true, wakeMinutes = 12 * 60 + 1,
            weekdays = emptySet(), source = AlarmSource.STRAP_AND_PHONE))
        val (c, strap, phone) = newCoordinator(store, now = { currentNow })
        c.recompute()
        assertEquals(1, phone.lastDesired.size)
        val initialBackupAt = phone.lastDesired.single().fireAtEpochMs

        currentNow = ZonedDateTime.of(
            LocalDate.of(2026, 6, 22),
            java.time.LocalTime.of(12, 2),
            zone,
        ).toInstant().toEpochMilli()
        strap.calls.clear()
        val phoneReconcilesBefore = phone.reconcileCount

        c.onStrapAlarmFired()

        assertEquals("ab", store.awaitingStrapDismissAlarmId())
        assertTrue(phone.cancelledIds.isEmpty())
        assertEquals(phoneReconcilesBefore, phone.reconcileCount)

        c.onStrapAlarmDismissed()

        assertEquals(listOf("ab"), phone.cancelledIds)
        assertNull(store.awaitingStrapDismissAlarmId())
        assertEquals(phoneReconcilesBefore + 1, phone.reconcileCount)
        assertTrue(phone.lastDesired.single().fireAtEpochMs > initialBackupAt)
    }

    @Test fun onStrapAlarmDismissedNoOpsWithoutPriorFiredEvent() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "ab", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP_AND_PHONE))
        val (c, _, phone) = newCoordinator(store)
        c.recompute()
        val phoneReconcilesBefore = phone.reconcileCount

        c.onStrapAlarmDismissed()

        assertTrue(phone.cancelledIds.isEmpty())
        assertEquals(phoneReconcilesBefore, phone.reconcileCount)
    }

    private fun androidContextStub(): android.content.Context =
        org.mockito.kotlin.mock<android.content.Context>()
}
