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

    /** The position the given player is playing at, if we play it ourselves. */
    fun forPlayer(playerId: PlayerId?): Duration? = current?.takeIf { it.first == playerId }?.second

    fun update(playerId: PlayerId, position: Duration) {
        current = playerId to position
    }

    fun clear() {
        current = null
    }
}
