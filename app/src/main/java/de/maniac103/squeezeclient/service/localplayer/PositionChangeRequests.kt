package de.maniac103.squeezeclient.service.localplayer

import android.os.SystemClock

/**
 * The times at which the app asked the server to move the playback position or switch track, and
 * - for a position - the position it asked for. The stream the server starts right afterwards
 * plays that position, while the position the server reports for it can be off: it counts ahead
 * while it streams, no matter how much of it the player really consumed (see LocalPlaybackService).
 */
object PositionChangeRequests {
    private const val WINDOW_MS = 3_000L

    @Volatile
    private var lastRequest = 0L

    /** The position the last request asked for, when it was a position request. */
    @Volatile
    private var lastRequestedPosition: Int? = null

    fun note(positionSeconds: Int? = null) {
        lastRequest = SystemClock.elapsedRealtime()
        lastRequestedPosition = positionSeconds
    }

    fun isRecent() = SystemClock.elapsedRealtime() - lastRequest < WINDOW_MS

    /**
     * The position the last request asked for, as long as a stream start can still be its
     * consequence. Unlike the server's own position this is where the stream really plays, so it
     * is the better base for a stream the server started because we asked for a position.
     */
    fun requestedPosition() = lastRequestedPosition.takeIf { isRecent() }

    /** Forgets the last request, for requests the local player sends itself. */
    fun clear() {
        lastRequest = 0L
        lastRequestedPosition = null
    }
}
