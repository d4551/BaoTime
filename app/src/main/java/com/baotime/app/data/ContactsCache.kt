package com.baotime.app.data

import com.baotime.app.config.AppConfig
import com.baotime.app.network.BlueBubblesClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class MacContactsState { Loading, Ready, NotConfigured, Unavailable }

data class ContactMatch(val contact: DeviceContact, val macRevision: Long?) {
    internal val identity get() = contact to (macRevision != null)
}

data class ContactMatches(val device: List<ContactMatch>, val iMessage: List<ContactMatch>) {
    val all = iMessage + device
}

/** Device results and the latest Mac response for one configuration revision. */
data class ContactsSnapshot(
    val device: ContactsOutcome,
    private val remote: BlueBubblesClient.ServerContacts?,
    val configurationRevision: Long,
    private val macConfigured: Boolean
) {
    private val deviceContacts = when (device) {
        is ContactsOutcome.Records -> device.contacts
        ContactsOutcome.Denied -> emptyList()
    }
    private val iMessage = if (remote == null) emptyList() else {
        val deviceKeys = deviceContacts.map { AppConfig.favoriteKey(it.primaryAddress) }.toSet()
        remote.contacts.filter { AppConfig.favoriteKey(it.primaryAddress) !in deviceKeys }
    }
    val macState = when {
        !macConfigured -> MacContactsState.NotConfigured
        remote == null -> MacContactsState.Loading
        remote.reachable -> MacContactsState.Ready
        else -> MacContactsState.Unavailable
    }
    private val matches = ContactMatches(deviceContacts.map { ContactMatch(it, null) },
        iMessage.map { ContactMatch(it, configurationRevision) })

    fun search(query: String): ContactMatches {
        val term = query.trim()
        if (term.isEmpty()) return matches
        fun matchesTerm(match: ContactMatch): Boolean = match.contact.displayName.contains(term, ignoreCase = true) ||
            match.contact.primaryAddress.contains(term, ignoreCase = true)
        return ContactMatches(matches.device.filter(::matchesTerm), matches.iMessage.filter(::matchesTerm))
    }
}

/** Activity-owned cache; a refresh cannot publish bytes from an obsolete server configuration. */
class ContactsCache(
    private val device: ContactsRepository,
    private val server: BlueBubblesClient,
    private val config: AppConfig
) {
    private var storedSnapshot: ContactsSnapshot? = null
    val snapshot: ContactsSnapshot?
        get() = storedSnapshot?.let(::current)

    fun current(snapshot: ContactsSnapshot): ContactsSnapshot {
        val (url, revision) = config.connectionDestination()
        return if (snapshot.configurationRevision == revision) snapshot
        else ContactsSnapshot(snapshot.device, null, revision, url.isNotEmpty())
    }

    suspend fun refresh(revision: Long, render: (ContactsSnapshot) -> Unit) {
        suspend fun publish(snapshot: ContactsSnapshot): Boolean {
            currentCoroutineContext().ensureActive()
            val changed = storedSnapshot != snapshot
            if (!config.admitConnectionOperation(revision) {
                    if (changed) storedSnapshot = snapshot
                    true
                }) return false
            if (changed) render(snapshot)
            return true
        }

        val outcome = device.queryContacts()
        val (url, currentRevision) = config.connectionDestination()
        if (revision != currentRevision) return
        val local = storedSnapshot?.takeIf { it.configurationRevision == revision }?.copy(device = outcome)
            ?: ContactsSnapshot(outcome, null, revision, url.isNotEmpty())
        if (!publish(local) || url.isEmpty()) return
        publish(local.copy(remote = server.fetchContacts(revision)))
    }
}
