/*
 * This file is part of Squeeze Client, an Android client for the LMS music server.
 * Copyright (c) 2026 Danny Baumann
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

package de.maniac103.squeezeclient.service.mediasession

import de.maniac103.squeezeclient.model.PlayerStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal data class PendingPlayerCommands(
    val playlistPosition: Int? = null,
    val positionInTrack: Duration? = null,
    val playState: PlayerStatus.PlayState? = null,
    val playlistTimestamp: Instant? = null
) {
    fun seekTo(
        position: Int,
        positionInTrack: Duration,
        timestamp: Instant,
        trackCount: Int,
        repeatAll: Boolean
    ): PendingPlayerCommands {
        val target = when {
            trackCount == 0 -> null
            repeatAll -> Math.floorMod(position, trackCount)
            position in 0 until trackCount -> position
            else -> null
        }
        return copy(
            playlistPosition = target,
            positionInTrack = positionInTrack.takeIf { target != null },
            playlistTimestamp = timestamp
        )
    }

    fun acknowledge(
        position: Int,
        positionInTrack: Duration?,
        playState: PlayerStatus.PlayState,
        timestamp: Instant
    ): PendingPlayerCommands? {
        val samePlaylist = timestamp == playlistTimestamp
        val positionReached = playlistPosition == null || position == playlistPosition
        val seekReached = positionReached && this.positionInTrack != null &&
            positionInTrack != null && (positionInTrack - this.positionInTrack).absoluteValue <=
            1.seconds
        return copy(
            playlistPosition = playlistPosition.takeUnless { !samePlaylist || positionReached },
            positionInTrack = this.positionInTrack.takeUnless { !samePlaylist || seekReached },
            playState = this.playState.takeUnless { it == playState }
        ).takeUnless {
            it.playlistPosition == null && it.positionInTrack == null && it.playState == null
        }
    }
}
