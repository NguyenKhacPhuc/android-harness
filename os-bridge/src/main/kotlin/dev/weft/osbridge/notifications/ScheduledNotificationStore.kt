package dev.weft.osbridge.notifications

import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduledNotification
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists the list of pending scheduled notifications. Needed because
 * AlarmManager does not expose a "list pending alarms" API — if we want
 * Notifications.listScheduled() to work we have to keep our own registry.
 *
 * Storage is abstracted behind [StringKeyStore] so this can be unit-tested
 * without Android framework. The Android impl plugs in a
 * SharedPreferences-backed [StringKeyStore].
 */
class ScheduledNotificationStore(private val backing: StringKeyStore) {

    private val json = Json { encodeDefaults = true }

    fun add(entry: ScheduledNotification) {
        val key = key(entry.handle)
        backing.put(key, json.encodeToString(StoredEntry.serializer(), entry.toStored()))
    }

    fun remove(handle: NotificationHandle): Boolean {
        val key = key(handle)
        return backing.remove(key)
    }

    fun get(handle: NotificationHandle): ScheduledNotification? =
        backing.get(key(handle))?.let { json.decodeFromString(StoredEntry.serializer(), it).toScheduled() }

    /** Return all entries, optionally filtered. */
    fun list(filter: ScheduleFilter? = null): List<ScheduledNotification> {
        val all = backing.keys(PREFIX).mapNotNull { backing.get(it) }
            .map { json.decodeFromString(StoredEntry.serializer(), it).toScheduled() }

        if (filter == null) return all
        return all.filter { entry ->
            (filter.beforeIso?.let { entry.nextRunIso <= it } ?: true) &&
                (filter.afterIso?.let { entry.nextRunIso >= it } ?: true)
        }
    }

    private fun key(handle: NotificationHandle): String = "$PREFIX${handle.id}"

    companion object {
        const val PREFIX = "sched."
    }
}

/**
 * Minimal storage seam. The Android implementation is backed by
 * SharedPreferences; tests use [InMemoryStringKeyStore].
 */
interface StringKeyStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String): Boolean
    /** All keys that start with [prefix]. */
    fun keys(prefix: String): List<String>
}

/** In-memory implementation for tests. Not thread-safe. */
class InMemoryStringKeyStore(initial: Map<String, String> = emptyMap()) : StringKeyStore {
    private val map = initial.toMutableMap()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String): Boolean = map.remove(key) != null
    override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
}

@Serializable
private data class StoredEntry(
    val id: String,
    val title: String,
    val body: String? = null,
    val channelId: String? = null,
    val tapToolName: String? = null,
    val tapParamsJson: String? = null,
    val nextRunIso: String,
)

private fun ScheduledNotification.toStored(): StoredEntry = StoredEntry(
    id = handle.id,
    title = spec.title,
    body = spec.body,
    channelId = spec.channelId,
    tapToolName = spec.tapAction?.tool,
    tapParamsJson = spec.tapAction?.params?.toString(),
    nextRunIso = nextRunIso,
)

private fun StoredEntry.toScheduled(): ScheduledNotification = ScheduledNotification(
    handle = NotificationHandle(id),
    spec = NotificationSpec(
        title = title,
        body = body,
        channelId = channelId,
        // tapAction is omitted here; rehydration of arbitrary JSON params lives in the receiver.
    ),
    nextRunIso = nextRunIso,
)
