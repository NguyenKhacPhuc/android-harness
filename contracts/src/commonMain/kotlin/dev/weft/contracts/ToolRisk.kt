package dev.weft.contracts

/**
 * Coarse risk classification for a tool. Drives the approval gate +
 * read-only-mode enforcement in the substrate.
 *
 * Tools derive a default risk from their existing `destructive` /
 * `sideEffecting` flags; authors can override when those defaults
 * mis-classify (e.g. a sideEffecting "log_event" that's actually
 * harmless).
 */
enum class ToolRisk {
    /** No mutation, no external side-effects. `data_read`, `system_user_context`. */
    Read,
    /** Mutates persistent state or has user-visible side effects. `data_update`, `notify_show`. */
    Write,
    /** Hard to reverse and/or visible beyond the app. `data_delete`, `send_email`. */
    Destructive,
}
