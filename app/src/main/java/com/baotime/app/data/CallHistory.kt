package com.baotime.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

/** Persisted call-history store behind [CallHistoryCodec]. */
class CallHistory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stores one call. */
    suspend fun recordCall(
        handleOrNumber: String,
        displayName: String,
        faceTimeUrl: String?,
        callType: Int,
        durationSec: Long = 0L,
        timestampMs: Long = System.currentTimeMillis()
    ): BaoCallRecord = withContext(Dispatchers.IO) { synchronized(prefs) {
        if (displayName.isBlank()) {
            throw IllegalArgumentException("displayName must be resolved by the caller")
        }
        val entry = BaoCallRecord(
            id = "${BaoCallRecord.ID_PREFIX}${java.util.UUID.randomUUID()}",
            displayName = displayName,
            handleOrNumber = handleOrNumber,
            faceTimeUrl = faceTimeUrl,
            timestampMs = timestampMs,
            durationSec = durationSec,
            callType = callType,
            isFaceTime = faceTimeUrl != null
        )
        persist(decodedRecords() + entry)
        entry
    } }

    /** Stores elapsed local session time against the exact record, including during teardown. */
    fun setDuration(recordId: String, durationSec: Long) = synchronized(prefs) {
        val records = decodedRecords().map { record ->
            if (record.id == recordId) record.copy(durationSec = durationSec) else record
        }
        persist(records)
    }

    /**
     * Newest-first history, capped at [limit].
     * @throws JSONException when the store is structurally corrupt.
     */
    suspend fun history(limit: Int = 50): List<BaoCallRecord> = withContext(Dispatchers.IO) {
        decodedRecords().sortedByDescending { it.timestampMs }.take(limit)
    }

    /** Deletes one record by id (from the record-detail sheet). */
    suspend fun deleteRecord(id: String) = withContext(Dispatchers.IO) {
        synchronized(prefs) { persist(decodedRecords().filterNot { it.id == id }) }
    }

    /** Clears the whole history (Recents "Clear history" action). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        synchronized(prefs) { persist(emptyList()) }
    }

    /** Records currently readable from the store. */
    private fun decodedRecords(): List<BaoCallRecord> =
        CallHistoryCodec.decode(prefs.getString(KEY_RECORDS, null))

    private fun persist(records: List<BaoCallRecord>) {
        val capped = records.sortedByDescending { it.timestampMs }.take(MAX_RECORDS)
        prefs.edit { putString(KEY_RECORDS, CallHistoryCodec.encode(capped)) }
    }

    private companion object {
        const val PREFS_NAME = "baotime_call_history"
        const val KEY_RECORDS = "records_json"
        const val MAX_RECORDS = 100
    }
}
