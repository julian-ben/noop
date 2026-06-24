package com.noop.alarm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted list of UnifiedAlarms plus the one nullable strap-armed id. JSON-backed in a dedicated
 * SharedPreferences file. Display order is user-controlled (drag-reorder).
 *
 * The coordinator is the only thing that writes armedStrapAlarmId. The screen layer never touches
 * it.
 */
class UnifiedAlarmStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _alarms = MutableStateFlow(loadAlarms())
    val alarms: StateFlow<List<UnifiedAlarm>> = _alarms.asStateFlow()

    /** True if at least one enabled alarm has smart wake enabled. Used by the BLE service. */
    val smartWakeOn: Boolean get() = _alarms.value.any { it.enabled && it.smartWake }

    private val _armedStrap = MutableStateFlow(prefs.getString(KEY_ARMED_STRAP_ID, null))
    val armedStrapAlarmId: StateFlow<String?> = _armedStrap.asStateFlow()

    /** Alarm ids with phone-side AlarmManager entries currently registered. */
    fun scheduledPhoneAlarmIds(): Set<String> =
        prefs.getStringSet(KEY_SCHEDULED_PHONE_IDS, emptySet())?.toSet().orEmpty()

    fun setScheduledPhoneAlarmIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SCHEDULED_PHONE_IDS, ids).apply()
    }

    /** Strap alarm id that fired and is waiting for the firmware disabled/dismissed event. */
    fun awaitingStrapDismissAlarmId(): String? = prefs.getString(KEY_AWAITING_STRAP_DISMISS_ID, null)

    fun setAwaitingStrapDismissAlarmId(id: String?) {
        prefs.edit().apply {
            if (id.isNullOrBlank()) remove(KEY_AWAITING_STRAP_DISMISS_ID) else putString(KEY_AWAITING_STRAP_DISMISS_ID, id)
        }.apply()
    }

    val migrationComplete: Boolean
        get() = prefs.getBoolean(KEY_MIGRATED, false)

    fun add(alarm: UnifiedAlarm) = mutate { it + alarm.sanitized() }

    fun update(id: String, alarm: UnifiedAlarm) = mutate { list ->
        list.map { if (it.id == id) alarm.copy(id = id).sanitized() else it }
    }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    fun reorder(fromIndex: Int, toIndex: Int) = mutate { list ->
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) list
        else {
            val mutable = list.toMutableList()
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            mutable.toList()
        }
    }

    fun setArmedStrapAlarmId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ARMED_STRAP_ID) else putString(KEY_ARMED_STRAP_ID, id)
        }.apply()
        _armedStrap.value = id
    }

    private fun mutate(op: (List<UnifiedAlarm>) -> List<UnifiedAlarm>) {
        val next = op(_alarms.value)
        prefs.edit().putString(KEY_ALARMS, json.encodeToString(next)).apply()
        _alarms.value = next
    }

    private fun loadAlarms(): List<UnifiedAlarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<UnifiedAlarm>>(raw) }
            .getOrDefault(emptyList())
            .map { it.sanitized() }
    }

    companion object {
        const val PREFS_NAME = "noop_alarm"
        const val KEY_ALARMS = "alarms"
        const val KEY_ARMED_STRAP_ID = "armedStrapAlarmId"
        const val KEY_SCHEDULED_PHONE_IDS = "scheduledPhoneAlarmIds"
        const val KEY_AWAITING_STRAP_DISMISS_ID = "awaitingStrapDismissAlarmId"
        const val KEY_MIGRATED = "noop.alarm.migrated"

        fun from(context: Context): UnifiedAlarmStore =
            UnifiedAlarmStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
