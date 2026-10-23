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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPlaybackPositionTest {
    @Test
    fun flacSeekDoesNotDoubleTheAbsoluteTimestamp() {
        val stream = StreamPlaybackPosition(1, 68.seconds)
        assertEquals(69.seconds, stream.position(69.seconds, absolute = true))
        assertEquals(68.seconds, stream.position(Duration.ZERO, absolute = true))
    }

    @Test
    fun relativeStreamsRetainTheirSeekOffset() {
        val stream = StreamPlaybackPosition(1, 68.seconds)
        assertEquals(69.seconds, stream.position(1.seconds, absolute = false))
    }

    @Test
    fun serverElapsedTimeExcludesTheFlacSeekOffset() {
        val stream = StreamPlaybackPosition(1, 68.seconds)
        assertEquals(Duration.ZERO, stream.streamPosition(Duration.ZERO, absolute = true))
        stream.recordFirstTimestamp(68.seconds)
        assertEquals(1.seconds, stream.streamPosition(69.seconds, absolute = true))
        assertEquals(69.seconds, stream.position(69.seconds, absolute = true))
    }

    @Test
    fun serverInitiatedSeekUsesTheFirstDecodedTimestamp() {
        val stream = StreamPlaybackPosition(1, Duration.ZERO)
        stream.recordFirstTimestamp(68.seconds)
        assertEquals(1.seconds, stream.streamPosition(69.seconds, absolute = true))
        assertEquals(69.seconds, stream.position(69.seconds, absolute = true))
    }

    @Test
    fun decodedNextTrackDoesNotInheritThePreviousTracksRecoveryPosition() {
        val stream = StreamPlaybackPosition(1, 300.seconds)
        stream.recordFirstTimestamp(Duration.ZERO)
        assertEquals(Duration.ZERO, stream.position(Duration.ZERO, absolute = true))
        assertEquals(1.seconds, stream.position(1.seconds, absolute = true))
        assertEquals(30.seconds, stream.position(30.seconds, absolute = true))
    }

    @Test
    fun decodedResumePositionOverridesTheSavedEstimate() {
        val stream = StreamPlaybackPosition(1, 68.seconds)
        stream.recordFirstTimestamp(67.seconds)
        assertEquals(67.seconds, stream.position(Duration.ZERO, absolute = true))
        assertEquals(67.5.seconds, stream.position(67.5.seconds, absolute = true))
        assertEquals(0.5.seconds, stream.streamPosition(67.5.seconds, absolute = true))
    }

    @Test
    fun decodedSeekPositionIsRetainedWhileThePlaybackClockStarts() {
        val stream = StreamPlaybackPosition(1, 68.seconds)
        stream.recordFirstTimestamp(68.seconds)
        assertEquals(68.seconds, stream.position(Duration.ZERO, absolute = true))
        assertEquals(69.seconds, stream.position(69.seconds, absolute = true))
    }

    @Test
    fun aLoaderRetryCannotResetTheStreamsElapsedTime() {
        val stream = StreamPlaybackPosition(1, 68.seconds)
        stream.recordFirstTimestamp(68.seconds)
        stream.recordFirstTimestamp(90.seconds)
        assertEquals(23.seconds, stream.streamPosition(91.seconds, absolute = true))
        assertEquals(2.seconds, stream.streamPosition(2.seconds, absolute = false))
    }

    @Test
    fun preloadingTheNextTrackCannotChangeThePlayingTracksOffset() {
        val current = StreamPlaybackPosition(1, 68.seconds)
        val next = StreamPlaybackPosition(2, Duration.ZERO)
        assertEquals(69.seconds, current.position(1.seconds, absolute = false))
        assertEquals(1.seconds, next.position(1.seconds, absolute = false))
        next.offset = 20.seconds
        assertEquals(69.seconds, current.position(1.seconds, absolute = false))
    }
}
