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
import de.maniac103.squeezeclient.model.Playlist
import kotlin.time.Duration.Companion.seconds

class PositionChangeRequests(private val now: () -> Long = SystemClock::elapsedRealtime) {
    var playerId: PlayerId? = null
        set(value) {
            if (field != value) clear()
            field = value
        }
    private var pending: Pending? = null

    data class Pending(
        val positionSeconds: Int?,
        val expiresAt: Long,
        val playlistPosition: Int?,
        val song: Playlist.PlaylistItem?
    )

    fun note(
        playerId: PlayerId,
        positionSeconds: Int? = null,
        playlistPosition: Int? = null,
        song: Playlist.PlaylistItem? = null
    ): Pending? {
        if (this.playerId != playerId) return null
        return Pending(
            positionSeconds,
            now() + 3.seconds.inWholeMilliseconds,
            playlistPosition,
            song
        )
            .also { pending = it }
    }

    fun isRecent(playerId: PlayerId) = this.playerId == playerId &&
        pending?.let { now() < it.expiresAt } == true

    fun current(
        playerId: PlayerId,
        playlistPosition: Int? = null,
        song: Playlist.PlaylistItem? = null
    ): Pending? {
        val request = pending ?: return null
        if (!isRecent(playerId)) return null
        if (request.positionSeconds != null) {
            if (request.playlistPosition != null && playlistPosition != null &&
                request.playlistPosition != playlistPosition
            ) {
                return null
            }
            if (request.song != null && song != null && request.song != song) return null
        }
        return request
    }

    fun clear(request: Pending? = pending) {
        if (pending === request) pending = null
    }
}
