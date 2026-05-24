package dev.weft.osbridge.contacts

import android.content.Context
import android.provider.ContactsContract
import dev.weft.contracts.ContactFilter
import dev.weft.contracts.ContactSummary
import dev.weft.contracts.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Contacts]. Reads from the system Contacts
 * provider via ContentResolver. Requires READ_CONTACTS at runtime.
 *
 * Phase-3 scope:
 *   - Reads display name + emails + phones.
 *   - Filter: nameContains, hasEmail, hasPhone, limit.
 *   - No write side (substrate doesn't expose contact-write in v1; see
 *     `docs/05-script-catalog.md`).
 */
public class AndroidContacts(private val context: Context) : Contacts {

    override suspend fun read(filter: ContactFilter): List<ContactSummary> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )

        val selection = buildList {
            filter.nameContains?.let {
                add("${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?")
            }
            if (filter.hasPhone == true) {
                add("${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1")
            }
        }.joinToString(" AND ").ifEmpty { null }

        val selectionArgs: Array<String>? = filter.nameContains?.let { arrayOf("%$it%") }

        val results = mutableListOf<ContactSummary>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT ${filter.limit.coerceIn(1, MAX_LIMIT)}",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val hasPhone = cursor.getInt(hasPhoneIdx) > 0

                val phones = if (hasPhone) readPhones(id) else emptyList()
                val emails = readEmails(id)

                if (filter.hasEmail == true && emails.isEmpty()) continue

                results += ContactSummary(
                    id = id,
                    displayName = name,
                    emails = emails,
                    phones = phones,
                )
            }
        }
        results
    }

    private fun readPhones(contactId: String): List<String> {
        val resolver = context.contentResolver
        val phones = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                cursor.getString(idx)?.let { phones += it }
            }
        }
        return phones
    }

    private fun readEmails(contactId: String): List<String> {
        val resolver = context.contentResolver
        val emails = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                cursor.getString(idx)?.let { emails += it }
            }
        }
        return emails
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
