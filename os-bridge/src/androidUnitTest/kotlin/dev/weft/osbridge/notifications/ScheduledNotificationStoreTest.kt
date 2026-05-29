package dev.weft.osbridge.notifications

import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduledNotification
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ScheduledNotificationStoreTest : StringSpec({

    fun store(): Pair<ScheduledNotificationStore, InMemoryStringKeyStore> {
        val backing = InMemoryStringKeyStore()
        return ScheduledNotificationStore(backing) to backing
    }

    fun entry(id: String, iso: String, title: String = "title-$id"): ScheduledNotification =
        ScheduledNotification(
            handle = NotificationHandle(id),
            spec = NotificationSpec(title = title, body = "body"),
            nextRunIso = iso,
        )

    "add then get returns the same entry" {
        val (s, _) = store()
        val e = entry("n1", "2026-06-01T09:00:00Z")
        s.add(e)
        s.get(NotificationHandle("n1")).shouldNotBeNull().handle shouldBe e.handle
    }

    "remove makes get return null and trims the underlying store" {
        val (s, backing) = store()
        s.add(entry("n1", "2026-06-01T09:00:00Z"))
        s.remove(NotificationHandle("n1")) shouldBe true
        s.get(NotificationHandle("n1")).shouldBeNull()
        backing.keys("").shouldHaveSize(0)
    }

    "list returns all entries when no filter is provided" {
        val (s, _) = store()
        s.add(entry("a", "2026-06-01T09:00:00Z"))
        s.add(entry("b", "2026-06-02T09:00:00Z"))
        s.add(entry("c", "2026-06-03T09:00:00Z"))
        s.list().map { it.handle.id }.shouldContainExactlyInAnyOrder("a", "b", "c")
    }

    "filter by beforeIso excludes later entries" {
        val (s, _) = store()
        s.add(entry("a", "2026-06-01T09:00:00Z"))
        s.add(entry("b", "2026-06-05T09:00:00Z"))
        s.add(entry("c", "2026-06-10T09:00:00Z"))

        val before = s.list(ScheduleFilter(beforeIso = "2026-06-05T09:00:00Z"))
        before.map { it.handle.id }.shouldContainExactlyInAnyOrder("a", "b")
    }

    "filter by afterIso excludes earlier entries" {
        val (s, _) = store()
        s.add(entry("a", "2026-06-01T09:00:00Z"))
        s.add(entry("b", "2026-06-05T09:00:00Z"))
        s.add(entry("c", "2026-06-10T09:00:00Z"))

        val after = s.list(ScheduleFilter(afterIso = "2026-06-05T09:00:00Z"))
        after.map { it.handle.id }.shouldContainExactlyInAnyOrder("b", "c")
    }
})
