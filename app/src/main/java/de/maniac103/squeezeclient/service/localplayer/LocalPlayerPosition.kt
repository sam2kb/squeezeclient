package de.maniac103.squeezeclient.service.localplayer

import de.maniac103.squeezeclient.model.PlayerId
import kotlin.time.Duration

/**
 * The position the player built into the app is playing at. Displaying the server's position
 * instead makes the position drift, as the server keeps counting while we are disconnected and
 * resumes at the position it deduced itself when it restarts its stream.
 */
object LocalPlayerPosition {
    @Volatile
    private var current: Pair<PlayerId, Duration>? = null

    /** Duration of the song currently being played, as reported by the server. */
    @Volatile
    var songDuration: Duration? = null
        private set

    /** Increases whenever the song the app plays (for the local player) changes. */
    @Volatile
    var songGeneration = 0
        private set

    /**
     * The position the given player is playing at, if we play it ourselves. Never beyond the
     * song's end: a stream can play longer than the song (e.g. when the server appended the next
     * track to it), and reporting such a position confuses both the server and the UI.
     */
    fun forPlayer(playerId: PlayerId?): Duration? =
        current?.takeIf { it.first == playerId }?.second?.let { position ->
            songDuration?.let { position.coerceIn(Duration.ZERO, it) } ?: position
        }

    /** Clamps a position to the song's duration, so nothing reports a position past its end. */
    fun clamp(position: Duration): Duration =
        songDuration?.let { position.coerceIn(Duration.ZERO, it) } ?: position

    /** Duration of the song currently being played, as reported by the server. */
    fun updateDuration(duration: Duration?) {
        songDuration = duration
    }

    fun noteSongChanged() {
        songGeneration++
    }

    fun update(playerId: PlayerId, position: Duration) {
        current = playerId to position
    }

    fun clear() {
        current = null
    }
}
