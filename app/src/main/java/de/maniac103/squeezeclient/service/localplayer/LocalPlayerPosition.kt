/*
 * This file is part of Squeeze Client, an Android client for the LMS music server.
 * Copyright (c) 2025 Danny Baumann
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 */

package de.maniac103.squeezeclient.service.localplayer

import android.os.SystemClock
import de.maniac103.squeezeclient.model.PlayerId
import de.maniac103.squeezeclient.model.PlayerStatus
import de.maniac103.squeezeclient.model.Playlist
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LocalPlayerPosition(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var current: Sample? = null
    var server: String? = null
    private data class Sample(
        val playerId: PlayerId,
        val index: Int,
        val song: Playlist.PlaylistItem?,
        val position: Duration,
        val playing: Boolean,
        val timestamp: Long
    )

    fun update(playerId: PlayerId, status: PlayerStatus, position: Duration, playing: Boolean) {
        current = Sample(
            playerId,
            status.playlist.currentPosition,
            status.playlist.nowPlaying,
            position,
            playing,
            now()
        )
    }

    fun forPlayer(playerId: PlayerId?, status: PlayerStatus): Duration? {
        val sample = current?.takeIf {
            it.playerId == playerId && it.index == status.playlist.currentPosition &&
                it.song == status.playlist.nowPlaying && status.playlist.trackCount > 0
        } ?: return null
        val position = sample.position + if (sample.playing) {
            (now() - sample.timestamp).coerceAtLeast(0).milliseconds
        } else {
            Duration.ZERO
        }
        return status.currentSongDuration?.let {
            position.coerceIn(Duration.ZERO, it.coerceAtLeast(Duration.ZERO))
        } ?: position.coerceAtLeast(Duration.ZERO)
    }

    fun clear(playerId: PlayerId) {
        if (current?.playerId == playerId) current = null
    }
}
