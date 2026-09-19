package de.maniac103.squeezeclient.service.localplayer

import de.maniac103.squeezeclient.model.PlayerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * The position the player built into the app is playing at. The server's own position model
 * drifts away from it: it keeps advancing while we are disconnected and jumps by the amount it
 * had buffered when it restarts its stream, so the app displays this position while the local
 * player is the one playing.
 */
object LocalPlayerPosition {
    private val _position = MutableStateFlow<Pair<PlayerId, Duration>?>(null)
    val position: StateFlow<Pair<PlayerId, Duration>?> = _position.asStateFlow()

    /** The position the given player is playing at, if we play it ourselves. */
    fun forPlayer(playerId: PlayerId?): Duration? =
        _position.value?.takeIf { it.first == playerId }?.second

    fun update(playerId: PlayerId, position: Duration) {
        _position.value = playerId to position
    }

    fun clear() {
        _position.value = null
    }
}
