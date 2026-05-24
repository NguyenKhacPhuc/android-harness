package dev.weft.oauth

import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Pipe for delivering OAuth redirect URIs from the host activity into the
 * suspending [OAuthClient.authorize] call.
 *
 * The OS routes the OAuth redirect (e.g. `undercurrent://oauth/linear`)
 * to whichever activity declared the matching `<intent-filter>`. That
 * activity must hand the URI back to the substrate; this channel is the
 * mechanism.
 *
 * Wiring in the host app's `MainActivity`:
 *
 * ```kotlin
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     intent.data?.let { substrate.oauthCallbackChannel.submit(it) }
 * }
 *
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     intent.data?.let { substrate.oauthCallbackChannel.submit(it) }
 *     // …
 * }
 * ```
 *
 * One channel is shared across all OAuth flows; concurrent authorizations
 * are filtered by the per-flow `state` parameter so callbacks don't
 * cross-talk.
 */
public class OAuthCallbackChannel {

    private val _callbacks: MutableSharedFlow<Uri> = MutableSharedFlow(
        replay = 0,
        // A small buffer absorbs the race where onNewIntent fires before
        // authorize()'s collector has subscribed (cold-start case).
        extraBufferCapacity = REPLAY_BUFFER,
    )

    /** Subscribe-only view. [OAuthClient] consumes from this. */
    public val callbacks: SharedFlow<Uri> = _callbacks.asSharedFlow()

    /**
     * Feed a redirect URI into the channel. Called by the host activity
     * from `onCreate` (for cold-start deep links) and `onNewIntent` (for
     * warm-start ones).
     *
     * Non-matching URIs (e.g. a deep link unrelated to OAuth) are no-ops
     * downstream — the collector filters by `state`.
     */
    public fun submit(uri: Uri) {
        _callbacks.tryEmit(uri)
    }

    private companion object {
        const val REPLAY_BUFFER = 8
    }
}
