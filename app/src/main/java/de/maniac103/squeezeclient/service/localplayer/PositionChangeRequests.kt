package de.maniac103.squeezeclient.service.localplayer

import android.os.SystemClock

/**
 * The times at which the app asked the server to move the playback position or switch track. The
 * stream the server starts right afterwards plays the position the server chose, so for those
 * streams its position is the truth - unlike for the restarts it does on its own, where we keep
 * ours (see LocalPlaybackService).
 */
object PositionChangeRequests {
    private const val WINDOW_MS = 3_000L

    @Volatile
    private var lastRequest = 0L

    fun note() {
        lastRequest = SystemClock.elapsedRealtime()
    }

    fun isRecent() = SystemClock.elapsedRealtime() - lastRequest < WINDOW_MS

    /** Forgets the last request, for requests the local player sends itself. */
    fun clear() {
        lastRequest = 0L
    }
}
