package dev.weft.osbridge.contacts

import dev.weft.contracts.ContactFilter
import dev.weft.contracts.ContactSummary
import dev.weft.contracts.Contacts
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Contacts.CNContact
import platform.Contacts.CNContactEmailAddressesKey
import platform.Contacts.CNContactFamilyNameKey
import platform.Contacts.CNContactFetchRequest
import platform.Contacts.CNContactGivenNameKey
import platform.Contacts.CNContactPhoneNumbersKey
import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityType
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNPhoneNumber
import platform.Foundation.NSError
import platform.Foundation.NSString
import kotlin.coroutines.resume

/**
 * iOS [Contacts] via the Contacts framework. Requests read access on
 * first use; returns an empty list when access is denied. The store is
 * enumerated and [ContactFilter] (nameContains substring, hasEmail /
 * hasPhone, limit) is applied in-Kotlin after fetch.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosContacts : Contacts {

    private val store = CNContactStore()

    override suspend fun read(filter: ContactFilter): List<ContactSummary> {
        if (!ensureAccess()) return emptyList()

        val keys = listOf(
            CNContactGivenNameKey,
            CNContactFamilyNameKey,
            CNContactEmailAddressesKey,
            CNContactPhoneNumbersKey,
        )

        val contacts = mutableListOf<CNContact>()
        val request = CNContactFetchRequest(keysToFetch = keys)
        store.enumerateContactsWithFetchRequest(request, error = null) { contact, _ ->
            if (contact != null) contacts += contact
        }

        val name = filter.nameContains?.takeIf { it.isNotBlank() }
        return contacts
            .map { it.toSummary() }
            .filter { summary ->
                (name == null || summary.displayName.contains(name, ignoreCase = true)) &&
                    (filter.hasEmail != true || summary.emails.isNotEmpty()) &&
                    (filter.hasPhone != true || summary.phones.isNotEmpty())
            }
            .take(filter.limit.coerceAtLeast(0))
    }

    private fun CNContact.toSummary(): ContactSummary {
        val emails = (emailAddresses as? List<*>).orEmpty()
            .filterIsInstance<CNLabeledValue>()
            .mapNotNull { (it.value as? NSString)?.toString() }
        val phones = (phoneNumbers as? List<*>).orEmpty()
            .filterIsInstance<CNLabeledValue>()
            .mapNotNull { (it.value as? CNPhoneNumber)?.stringValue }
        return ContactSummary(
            id = identifier,
            displayName = "$givenName $familyName".trim(),
            emails = emails,
            phones = phones,
        )
    }

    private suspend fun ensureAccess(): Boolean = suspendCancellableCoroutine { cont ->
        store.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, _: NSError? ->
            if (cont.isActive) cont.resume(granted)
        }
    }
}
