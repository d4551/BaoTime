package com.baotime.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException

class RecentCalls internal constructor(
    val records: List<BaoCallRecord>,
    val missedCount: Int,
    private val storedRecords: List<BaoCallRecord>
) {
    fun record(id: String): BaoCallRecord? = storedRecords.firstOrNull { it.id == id }
}

internal sealed interface RecentsRead {
    data class Available(val recent: RecentCalls) : RecentsRead
    data object Unreadable : RecentsRead
}

/** Persisted call-history store behind [CallHistoryCodec]. */
class CallHistory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val committedChanges = MutableStateFlow(0L)
    internal val changes = committedChanges.asStateFlow()

    /** Stores one call. */
    suspend fun recordCall(
        handleOrNumber: String,
        displayName: String,
        faceTimeUrl: String?,
        callType: Int,
        durationSec: Long = 0L,
        timestampMs: Long = System.currentTimeMillis()
    ): BaoCallRecord = withContext(Dispatchers.IO) { synchronized(prefs) {
        val entry = newRecord(handleOrNumber, displayName, faceTimeUrl, callType, durationSec, timestampMs)
        persist(decodedRecords() + entry)
        entry
    } }

    internal suspend fun recordMissedCall(handle: String, displayName: String, url: String?) {
        withContext(Dispatchers.IO) {
            synchronized(prefs) {
                val records = readableRecords() ?: return@synchronized
                val entry = newRecord(handle, displayName, url, BaoCallRecord.TYPE_MISSED, 0L, System.currentTimeMillis())
                persist(records + entry)
            }
        }
    }

    /** Returns the admitted room identity. Unreadable history is preserved while the room can still open. */
    internal suspend fun admitCall(
        handleOrNumber: String,
        displayName: String,
        faceTimeUrl: String,
        callType: Int,
        admission: (String) -> Boolean
    ): String? = withContext(Dispatchers.IO) { synchronized(prefs) {
        val entry = newRecord(handleOrNumber, displayName, faceTimeUrl, callType, 0L, System.currentTimeMillis())
        val encoded = readableRecords()?.let { encodeRecords(it + entry) }
        if (!admission(entry.id)) return@synchronized null
        if (encoded != null) publish(encoded)
        entry.id
    } }

    private fun newRecord(handleOrNumber: String, displayName: String, faceTimeUrl: String?,
        callType: Int, durationSec: Long, timestampMs: Long
    ): BaoCallRecord {
        require(displayName.isNotBlank()) { "displayName must be resolved by the caller" }
        return BaoCallRecord(
            id = "${BaoCallRecord.ID_PREFIX}${java.util.UUID.randomUUID()}",
            displayName = displayName,
            handleOrNumber = handleOrNumber,
            faceTimeUrl = faceTimeUrl,
            timestampMs = timestampMs,
            durationSec = durationSec,
            callType = callType,
            isFaceTime = faceTimeUrl != null
        )
    }

    /** Stores elapsed local session time against the exact record, including during teardown. */
    suspend fun setDuration(recordId: String, durationSec: Long) = withContext(Dispatchers.IO) {
        synchronized(prefs) {
            val records = readableRecords() ?: return@synchronized
            if (records.none { it.id == recordId && it.durationSec != durationSec }) return@synchronized
            persist(records.map { if (it.id == recordId) it.copy(durationSec = durationSec) else it })
        }
    }

    /**
     * Newest-first history, capped at [limit].
     * @throws JSONException when the store is structurally corrupt.
     */
    suspend fun history(limit: Int = 50): List<BaoCallRecord> = withContext(Dispatchers.IO) {
        newest(decodedRecords(), limit)
    }

    /** Provides the displayed rows and whole-store badge from the same read. */
    internal suspend fun readRecents(limit: Int): RecentsRead = withContext(Dispatchers.IO) {
        val records = readableRecords() ?: return@withContext RecentsRead.Unreadable
        RecentsRead.Available(RecentCalls(newest(records, limit), countMissed(records), records))
    }

    private fun newest(records: List<BaoCallRecord>, limit: Int): List<BaoCallRecord> =
        records.sortedByDescending { it.timestampMs }.take(limit)

    /** Deletes one record by id (from the record-detail sheet). */
    suspend fun deleteRecord(id: String) = withContext(Dispatchers.IO) {
        synchronized(prefs) { persist(decodedRecords().filterNot { it.id == id }) }
    }

    /** Missed-call count over the whole store, independent of any display limit. */
    suspend fun missedCount(): Int = withContext(Dispatchers.IO) {
        countMissed(decodedRecords())
    }

    private fun countMissed(records: List<BaoCallRecord>): Int =
        records.count { it.callType == BaoCallRecord.TYPE_MISSED }

    /** Clears the whole history (Recents "Clear history" action). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        synchronized(prefs) { persist(emptyList()) }
    }

    /** Records currently readable from the store. */
    private fun decodedRecords(): List<BaoCallRecord> = when (val payload = prefs.all[KEY_RECORDS]) {
        null -> emptyList()
        is String -> CallHistoryCodec.decode(payload)
        else -> throw JSONException("Call history preference is not text")
    }

    private fun readableRecords(): List<BaoCallRecord>? = runCatching { decodedRecords() }.getOrElse { outcome ->
        if (outcome !is JSONException) throw outcome
        null
    }

    private fun encodeRecords(records: List<BaoCallRecord>): String =
        CallHistoryCodec.encode(records.sortedByDescending { it.timestampMs }.take(MAX_RECORDS))

    private fun persist(records: List<BaoCallRecord>) {
        publish(encodeRecords(records))
    }

    private fun publish(encoded: String) {
        if (prefs.all[KEY_RECORDS] == encoded) return
        prefs.edit { putString(KEY_RECORDS, encoded) }
        committedChanges.value++
    }

    private companion object {
        const val PREFS_NAME = "baotime_call_history"
        const val KEY_RECORDS = "records_json"
        const val MAX_RECORDS = 100
    }
}
