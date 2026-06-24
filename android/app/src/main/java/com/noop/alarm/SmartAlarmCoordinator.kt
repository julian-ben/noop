package com.noop.alarm

import android.content.Context
import com.noop.alarm.PhoneFire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/**
 * The runtime glue between UnifiedAlarmStore and the legacy phone/strap primitives.
 *
 * Spec triggers (call recompute() from each):
 *   1. Alarm list change (add/update/delete/setEnabled). reorder is NOT a trigger.
 *   2. BLE connect for a WHOOP - caller flips strapConnected = true then recomputes.
 *   3. Day rollover at local midnight - piggy-backs on the widget refresh tick.
 *   4. Smart-wake fire - call onSmartWakeFire() (not recompute()) so we both buzz and disable.
 *
 * Wire-up (Task 19): single instance owned by NoopApplication; observe its store via
 * coroutine on Dispatchers.Default; recompute on the four spec triggers.
 *
 * The ONLY thing outside SmartAlarmScheduler / WhoopBleClient that touches arm/disable/run.
 */
class SmartAlarmCoordinator(
    private val context: Context,
    private val store: UnifiedAlarmStore,
    private val nowEpochMs: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val strapArmer: StrapArmer,
    private val phoneScheduler: PhoneScheduler,
) {

    // Mirrors the persisted value in UnifiedAlarmStore. Updated on every recompute so it survives
    // process death: the store's prefs hold the ground truth and we shadow it in a StateFlow for
    // reactive observers (BLE service, test assertions).
    private val _armedStrapAlarmId = MutableStateFlow(store.armedStrapAlarmId.value)

    /** Test seam: the id we believe is armed on the firmware. Persists across process death. */
    val armedStrapAlarmId: StateFlow<String?> = _armedStrapAlarmId.asStateFlow()

    /**
     * Whether the strap is currently connected. Affects whether strap commands are queued or sent.
     * When set to true (BLE connect), the next recompute() will re-arm the firmware regardless of
     * whether the desired id matches the last-known armed id, since the firmware loses state on
     * disconnect.
     */
    var strapConnected: Boolean = false
        set(value) {
            val wasConnected = field
            field = value
            // Mark that firmware state is lost on (re-)connect so recompute re-arms.
            if (value && !wasConnected) _strapFirmwareArmed = false
        }

    // Tracks whether the firmware has actually received an armAt command in this connection session.
    // Set to false on BLE connect/disconnect so recompute() re-sends arm on the next trigger.
    private var _strapFirmwareArmed: Boolean = false

    /**
     * Called on every trigger (list change / BLE connect / midnight / smart-wake).
     * Recomputes the schedule and reconciles phone + strap state.
     */
    fun recompute() {
        recompute(reconcilePhone = true)
    }

    private fun recompute(reconcilePhone: Boolean) {
        val schedule = UnifiedAlarmResolver.resolveSchedule(
            store.alarms.value, nowEpochMs(), zone,
        )

        // Phone path: hand the whole desired list to the scheduler; it diffs internally.
        if (reconcilePhone) phoneScheduler.reconcile(schedule.phoneAlarms)

        // Strap path: compare desired head to what is currently armed.
        val desiredId = schedule.nextStrapArm?.alarmId
        val currentId = _armedStrapAlarmId.value

        if (desiredId == null) {
            // No strap alarm desired.
            if (currentId != null) {
                // Cancel whatever was armed.
                if (strapConnected) strapArmer.disable()
                _armedStrapAlarmId.value = null
                store.setArmedStrapAlarmId(null)
                _strapFirmwareArmed = false
            }
            // else: both null, nothing to do.
        } else {
            // A strap alarm is desired.
            val needsArm = currentId == null          // first arm
                || desiredId != currentId             // different alarm won
                || !_strapFirmwareArmed               // reconnect: firmware lost its state
            if (needsArm) {
                val nextFire = schedule.nextStrapArm
                if (strapConnected) {
                    if (currentId != null && desiredId != currentId) {
                        // Preempt: cancel the old one before arming the new one.
                        strapArmer.disable()
                    }
                    strapArmer.armAt(nextFire.wakeEpochMs / 1000L)
                    _strapFirmwareArmed = true
                    // Only update observable state when the strap has physically accepted the arm.
                    // While disconnected, leave the published state at its prior value so UI shows
                    // "not armed" until the firmware actually confirms receipt on reconnect.
                    _armedStrapAlarmId.value = desiredId
                    store.setArmedStrapAlarmId(desiredId)
                }
                // else: strap offline - do not claim the alarm is armed. The _strapFirmwareArmed = false
                // flag already ensures recompute() will re-arm on the next strapConnected = true trigger.
            }
            // else: desiredId == currentId and firmware already has it - no I/O needed.
        }
    }

    /**
     * Smart-wake fired (sleep watcher detected light sleep in window). Drives the strap
     * to buzz manually then disables the firmware alarm so it doesn't double-fire.
     *
     * No-op if the armed alarm is .phone-only (no strap to drive) or no alarm is armed.
     *
     * Note: Android's WhoopBleClient does not currently expose a runAlarm (command 68) primitive
     * the way iOS Commands.swift does. We call disable() so the original firmware buzz is
     * suppressed; if a runAlarm primitive lands on Android, hook it here before disable().
     */
    fun onSmartWakeFire() {
        if (_armedStrapAlarmId.value == null) return
        if (strapConnected) strapArmer.disable()
        _armedStrapAlarmId.value = null
        store.setArmedStrapAlarmId(null)
    }

    /** Called when the strap reports its firmware alarm fired. Firmware alarms are one-shot. */
    fun onStrapAlarmFired() {
        val firedId = _armedStrapAlarmId.value ?: return
        store.setAwaitingStrapDismissAlarmId(firedId)
        _armedStrapAlarmId.value = null
        store.setArmedStrapAlarmId(null)
        _strapFirmwareArmed = false
        // Event 57 means the strap alarm started, not that the user dismissed it. Keep any phone
        // backup for this occurrence armed until the phone alarm itself fires or an actual dismiss
        // signal exists; only re-arm the strap's next one-shot firmware alarm here.
        recompute(reconcilePhone = false)
    }

    /** Called when the strap reports its firmware alarm was disabled/dismissed after firing. */
    fun onStrapAlarmDismissed() {
        val dismissedId = store.awaitingStrapDismissAlarmId() ?: return
        phoneScheduler.cancel(dismissedId)
        store.setAwaitingStrapDismissAlarmId(null)
        recompute(reconcilePhone = true)
    }
}

/**
 * Seam over WhoopBleClient's strap-alarm primitives. The coordinator never touches the BLE
 * socket directly - it goes through this interface, which the application wires to
 * WhoopBleClient.armStrapAlarm / disableStrapAlarm.
 */
interface StrapArmer {
    /** Arm the strap to buzz at [epochSec] (seconds since Unix epoch). */
    fun armAt(epochSec: Long)

    /** Cancel any armed firmware alarm. */
    fun disable()
}

/**
 * Seam over SmartAlarmScheduler. The coordinator never touches AlarmManager directly - it
 * delegates to this interface, which the application wires to SmartAlarmScheduler.
 *
 * The implementation must cancel all previously scheduled phone alarms, then schedule exactly
 * the [desired] list. Request codes are keyed by alarm id; the impl owns that bookkeeping.
 */
interface PhoneScheduler {
    /**
     * Cancel everything we've scheduled, then schedule [desired] exactly. Keyed by alarm id.
     */
    fun reconcile(desired: List<PhoneFire>)

    /** Cancel a single scheduled phone alarm by id and forget its persisted registration. */
    fun cancel(alarmId: String)
}
