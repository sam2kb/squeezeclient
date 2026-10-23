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

import de.maniac103.squeezeclient.model.PlayerId
import de.maniac103.squeezeclient.model.PlayerStatus
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlayerPositionTest {
    private val local = PlayerId("local")
    private fun status(index: Int = 1, duration: Int = 200) = PlayerStatus(
        PlayerStatus.PlayState.Playing,
        PlayerStatus.PlaylistInfo(null, index, 2, Instant.fromEpochMilliseconds(0)),
        duration.seconds, 137F, PlayerStatus.RepeatState.Off, PlayerStatus.ShuffleState.Off,
        "player", true, true, 50, false, null, null, Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun actualPlaybackClockOverridesTheServersDrift() {
        var now = 100L
        val position = LocalPlayerPosition { now }
        val status = status()
        position.update(local, status, 68.seconds, true)
        now += 2_000L
        assertEquals(70.seconds, position.forPlayer(local, status))
        assertNull(position.forPlayer(PlayerId("remote"), status))
    }

    @Test
    fun pauseAndBufferingFreezeTheClock() {
        var now = 100L
        val position = LocalPlayerPosition { now }
        val status = status()
        position.update(local, status, 68.seconds, false)
        now += 120_000L
        assertEquals(68.seconds, position.forPlayer(local, status))
    }

    @Test
    fun anotherTrackCannotUseThePreviousPositionOrDuration() {
        val position = LocalPlayerPosition { 100L }
        val first = status()
        position.update(local, first, 180.seconds, true)
        assertNull(position.forPlayer(local, status(index = 2, duration = 30)))
        assertEquals(180.seconds, position.forPlayer(local, first))
        position.update(local, status(index = 2, duration = 30), 40.seconds, false)
        assertEquals(30.seconds, position.forPlayer(local, status(index = 2, duration = 30)))
        position.clear(PlayerId("remote"))
        assertNotNull(position.forPlayer(local, status(index = 2, duration = 30)))
        position.clear(local)
        assertNull(position.forPlayer(local, status(index = 2, duration = 30)))
    }
}
