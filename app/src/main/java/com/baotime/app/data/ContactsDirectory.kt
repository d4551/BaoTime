package com.baotime.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Contacts loading distinguishes permission denial from a successful read. */
sealed class ContactsOutcome {
    data object Denied : ContactsOutcome()
    data class Records(val contacts: List<DeviceContact>) : ContactsOutcome()
}

/** Reads phone numbers and email addresses from the device contacts provider. */
class ContactsRepository(private val context: Context) {

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Reads the complete address book. Filtering and presentation limits belong to the UI. */
    suspend fun queryContacts(): ContactsOutcome = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ContactsOutcome.Denied
        ContactsOutcome.Records((queryKind(PHONE_KIND) + queryKind(EMAIL_KIND))
            .sortedBy { it.displayName.lowercase() })
    }

    /**
     * Name lookup for a phone-number handle; null means no match or no permission.
     * Email handles are skipped (the phone lookup table has no Apple-ID rows).
     * Provider failures propagate to the caller.
     */
    suspend fun lookupContactName(phoneOrHandle: String): String? = withContext(Dispatchers.IO) {
        if (!hasPermission() || phoneOrHandle.isBlank() || phoneOrHandle.contains('@')) {
            return@withContext null
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneOrHandle)
        )
        context.contentResolver
            .query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    /** One cursor engine for every contact kind; only the kind spec differs. */
    private fun queryKind(kind: ContactKind): List<DeviceContact> {
        val contacts = mutableListOf<DeviceContact>()
        val seen = HashSet<String>()
        context.contentResolver.query(
            kind.uri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                kind.valueColumn
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val cid = cursor.getString(0).orEmpty()
                val name = cursor.getString(1).orEmpty()
                val address = cursor.getString(2).orEmpty().trim()
                if (address.isNotEmpty() && seen.add("$name|$address")) {
                    contacts.add(DeviceContact(cid, name.ifBlank { address }, address, isEmail = kind.isEmail))
                }
            }
        }
        return contacts
    }

    private data class ContactKind(val uri: Uri, val valueColumn: String, val isEmail: Boolean)

    private companion object {
        val PHONE_KIND = ContactKind(
            uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            valueColumn = ContactsContract.CommonDataKinds.Phone.NUMBER,
            isEmail = false
        )
        val EMAIL_KIND = ContactKind(
            uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            valueColumn = ContactsContract.CommonDataKinds.Email.ADDRESS,
            isEmail = true
        )
    }
}
