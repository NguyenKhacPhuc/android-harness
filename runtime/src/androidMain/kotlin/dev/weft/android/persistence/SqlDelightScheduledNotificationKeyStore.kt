package dev.weft.android.persistence

import dev.weft.osbridge.notifications.StringKeyStore
import dev.weft.android.db.WeftDatabase

/**
 * Persistent [StringKeyStore] — what `ScheduledNotificationStore`
 * needs to survive app restart. All entries live under namespace
 * [NAMESPACE].
 *
 * androidMain-only because the [StringKeyStore] interface itself lives
 * in `:os-bridge`'s androidMain (alongside the notifications module
 * that consumes it). When `:os-bridge` lifts to commonMain, this
 * implementation will follow.
 */
public class SqlDelightScheduledNotificationKeyStore(
    private val db: WeftDatabase,
) : StringKeyStore {

    override fun get(key: String): String? =
        db.keyValueQueries.get(NAMESPACE, key).executeAsOneOrNull()?.value_

    override fun put(key: String, value: String) {
        db.keyValueQueries.put(
            namespace = NAMESPACE,
            key = key,
            value_ = value,
            expires_at_ms = 0L,
        )
    }

    override fun remove(key: String): Boolean {
        val existed = get(key) != null
        if (existed) db.keyValueQueries.remove(NAMESPACE, key)
        return existed
    }

    override fun keys(prefix: String): List<String> =
        db.keyValueQueries.listKeys(NAMESPACE, prefix).executeAsList()

    public companion object {
        public const val NAMESPACE: String = "scheduled_notifications"
    }
}
