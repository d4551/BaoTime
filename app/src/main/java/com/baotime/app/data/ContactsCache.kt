package com.baotime.app.data

import com.baotime.app.config.AppConfig
import com.baotime.app.network.BlueBubblesClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A complete contact refresh, published atomically for both the tab and New call. */
data class ContactsSnapshot(
    val device: ContactsOutcome,
    val iMessage: List<DeviceContact>,
    val serverReachable: Boolean,
    val configurationRevision: Long
) {
    val allContacts: List<DeviceContact> get() = iMessage + when (device) {
        is ContactsOutcome.Records -> device.contacts
        ContactsOutcome.Denied -> emptyList()
    }
}

/** Activity-owned cache; a refresh cannot publish bytes from an obsolete server configuration. */
class ContactsCache(
    private val device: ContactsRepository,
    private val server: BlueBubblesClient,
    private val config: AppConfig
) {
    private val refreshLock = Mutex()
    var snapshot: ContactsSnapshot? = null
        private set

    suspend fun refresh(): ContactsSnapshot = refreshLock.withLock {
        var result: ContactsSnapshot? = null
        while (result == null) {
            currentCoroutineContext().ensureActive()
            val revision = config.connectionRevision()
            val outcome = device.queryContacts()
            val remote = server.fetchContacts()
            val deviceKeys = when (outcome) {
                is ContactsOutcome.Records -> outcome.contacts.map { AppConfig.favoriteKey(it.primaryAddress) }.toSet()
                ContactsOutcome.Denied -> emptySet()
            }
            if (revision == config.connectionRevision()) {
                result = ContactsSnapshot(outcome,
                    remote.contacts.filter { AppConfig.favoriteKey(it.primaryAddress) !in deviceKeys },
                    remote.reachable, revision)
                snapshot = result
            }
        }
        result
    }

    fun invalidate() { snapshot = null }

}
