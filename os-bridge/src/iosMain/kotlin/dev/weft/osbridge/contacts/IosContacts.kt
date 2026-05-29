package dev.weft.osbridge.contacts

import dev.weft.contracts.ContactFilter
import dev.weft.contracts.ContactSummary
import dev.weft.contracts.Contacts

/**
 * iOS stub for [Contacts]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `Contacts.CNContactStore` —
 * `enumerateContacts(with: CNContactFetchRequest)` or
 * `unifiedContacts(matching: CNContact.predicateForContacts(matchingName:))`,
 * pulling the `CNContactEmailAddressesKey` / `CNContactPhoneNumbersKey` keys.
 * Permission via `CNContactStore.requestAccess(for: .contacts)`.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosContacts : Contacts {
    override suspend fun read(filter: ContactFilter): List<ContactSummary> =
        TODO("IosContacts.read — wrap CNContactStore.enumerateContacts(with: CNContactFetchRequest)")
}
