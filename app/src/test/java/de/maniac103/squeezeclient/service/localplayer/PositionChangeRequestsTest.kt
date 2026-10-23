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

import de.maniac103.squeezeclient.model.JiveActions
import de.maniac103.squeezeclient.model.PlayerId
import de.maniac103.squeezeclient.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PositionChangeRequestsTest {
    @Test
    fun remoteCommandsDoNotReplaceTheLocalSeek() {
        val local = PlayerId("local")
        val requests = PositionChangeRequests { 100L }
        requests.playerId = local
        requests.note(local, 68)
        requests.note(PlayerId("remote"), 137)
        assertEquals(68, requests.current(local)?.positionSeconds)
        assertNull(requests.current(PlayerId("remote"))?.positionSeconds)
    }

    @Test
    fun failureOfAnOlderRequestDoesNotCancelTheNewSeek() {
        val local = PlayerId("local")
        val requests = PositionChangeRequests { 100L }
        requests.playerId = local
        val failed = requests.note(local, 68)
        requests.note(local, 137)
        requests.clear(failed)
        assertEquals(137, requests.current(local)?.positionSeconds)
        requests.clear(requests.current(local))
        assertFalse(requests.isRecent(local))
    }

    @Test
    fun playlistJumpOverridesAnEarlierSeek() {
        val local = PlayerId("local")
        val requests = PositionChangeRequests { 100L }
        requests.playerId = local
        requests.note(local, 68, playlistPosition = 1)
        requests.note(local, playlistPosition = 1)
        assertNull(requests.current(local)?.positionSeconds)
        assertEquals(true, requests.isRecent(local))
    }

    @Test
    fun seekForThePreviousTrackCannotChangeTheNextTrack() {
        val local = PlayerId("local")
        val requests = PositionChangeRequests { 100L }
        requests.playerId = local
        requests.note(local, 68, playlistPosition = 1)
        assertNull(requests.current(local, playlistPosition = 2))
        assertEquals(68, requests.current(local, playlistPosition = 1)?.positionSeconds)
    }

    @Test
    fun replacingTheSongAtTheSameIndexInvalidatesItsSeek() {
        val local = PlayerId("local")
        val requests = PositionChangeRequests { 100L }
        val actions =
            JiveActions(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null
            )
        val first = Playlist.PlaylistItem("first", "artist", "album", actions)
        val replacement = first.copy(title = "replacement")
        requests.playerId = local
        requests.note(local, 68, playlistPosition = 1, song = first)
        assertNull(requests.current(local, playlistPosition = 1, song = replacement))
        assertEquals(
            68,
            requests.current(local, playlistPosition = 1, song = first)?.positionSeconds
        )
    }

    @Test
    fun requestsExpireAndCannotSurvivePlayerReplacement() {
        var now = 100L
        val local = PlayerId("local")
        val requests = PositionChangeRequests { now }
        requests.playerId = local
        requests.note(local, 68)
        now = 3_100L
        assertNull(requests.current(local)?.positionSeconds)
        requests.note(local, 137)
        requests.playerId = PlayerId("replacement")
        assertNull(requests.current(PlayerId("replacement")))
    }
}
