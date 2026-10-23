package de.maniac103.squeezeclient.service.mediasession

import de.maniac103.squeezeclient.model.PlayerStatus.PlayState
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPlayerCommandsTest {
    private val timestamp = Instant.fromEpochSeconds(1)

    @Test
    fun oldStatusDoesNotUndoNextOrPause() {
        val pending = PendingPlayerCommands(playState = PlayState.Paused)
            .seekTo(5, 0.seconds, timestamp, 10, false)
        assertEquals(pending, pending.acknowledge(4, 20.seconds, PlayState.Playing, timestamp))
    }

    @Test
    fun playAcknowledgementDoesNotUndoNext() {
        val pending = PendingPlayerCommands(playState = PlayState.Paused)
            .seekTo(5, 0.seconds, timestamp, 10, false)
        val remaining = pending.acknowledge(4, 20.seconds, PlayState.Paused, timestamp)
        assertEquals(5, remaining?.playlistPosition)
        assertNull(remaining?.playState)
    }

    @Test
    fun trackAcknowledgementDoesNotUndoPause() {
        val pending = PendingPlayerCommands(playState = PlayState.Paused)
            .seekTo(5, 0.seconds, timestamp, 10, false)
        val remaining = pending.acknowledge(5, 0.seconds, PlayState.Playing, timestamp)
        assertNull(remaining?.playlistPosition)
        assertNull(remaining?.positionInTrack)
        assertEquals(PlayState.Paused, remaining?.playState)
    }

    @Test
    fun intermediateTrackDoesNotUndoRepeatedNext() {
        val first = PendingPlayerCommands().seekTo(5, 0.seconds, timestamp, 10, false)
        val second = first.seekTo(first.playlistPosition!! + 1, 0.seconds, timestamp, 10, false)
        assertEquals(second, second.acknowledge(5, 0.seconds, PlayState.Playing, timestamp))
        assertNull(second.acknowledge(6, 0.seconds, PlayState.Playing, timestamp))
    }

    @Test
    fun previousMovesBackAndRepeatWrapsAtQueueBoundaries() {
        val pending = PendingPlayerCommands()
        assertEquals(3, pending.seekTo(4 - 1, 0.seconds, timestamp, 10, false).playlistPosition)
        assertNull(pending.seekTo(-1, 0.seconds, timestamp, 10, false).playlistPosition)
        assertNull(pending.seekTo(10, 0.seconds, timestamp, 10, false).playlistPosition)
        assertEquals(9, pending.seekTo(-1, 0.seconds, timestamp, 10, true).playlistPosition)
        assertEquals(0, pending.seekTo(10, 0.seconds, timestamp, 10, true).playlistPosition)
        assertNull(pending.seekTo(0, 0.seconds, timestamp, 0, true).playlistPosition)
    }

    @Test
    fun changedQueueDiscardsTrackAndSeekButRetainsPause() {
        val pending = PendingPlayerCommands(playState = PlayState.Paused)
            .seekTo(5, 12.seconds, timestamp, 10, false)
        val remaining = pending.acknowledge(
            4,
            20.seconds,
            PlayState.Playing,
            Instant.fromEpochSeconds(2)
        )
        assertNull(remaining?.playlistPosition)
        assertNull(remaining?.positionInTrack)
        assertEquals(PlayState.Paused, remaining?.playState)
    }

    @Test
    fun oldPlaybackPositionDoesNotUndoSeek() {
        val pending = PendingPlayerCommands(
            positionInTrack = 60.seconds,
            playlistTimestamp = timestamp
        )
        assertEquals(pending, pending.acknowledge(4, 20.seconds, PlayState.Playing, timestamp))
        assertNull(pending.acknowledge(4, 60.5.seconds, PlayState.Playing, timestamp))
    }
}
