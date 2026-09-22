/*
 * This file is part of Squeeze Client, an Android client for the LMS music server.
 * Copyright (c) 2026 Danny Baumann
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 *
 */

package de.maniac103.squeezeclient.service.localplayer

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [DataReader] that keeps the start of a stream whose container needs it (see
 * [StreamContainer]) and replays it in front of a stream that resumes inside the file.
 *
 * The server streams a file as it is, so a stream resumed inside the file (which happens whenever
 * the server restores a playing session for a player that is not streaming yet, e.g. after an app
 * restart) does not contain the container's start, and such a stream cannot be decoded. The reader
 * therefore remembers the start of a stream that begins at the file's start and hands it back to
 * the player when a stream of that file is resumed without it.
 *
 * @param onStreamStartNeeded called when a resumed stream needs the stored stream start; it
 * triggers the lookup and returns whether the stream start can still arrive.
 * @param onStreamStartCaptured called with a captured stream start and the duration of the file
 * it belongs to (null when the container does not state one), so it can be stored for the right
 * track.
 */
@UnstableApi
class StreamPrefixCachingDataReader(
    private val upstream: DataReader,
    private val container: StreamContainer,
    private val cache: StreamStartCache,
    private val onStreamStartNeeded: () -> Boolean = { false },
    private val onStreamStartCaptured: (ByteArray, Double?) -> Unit = { _, _ -> }
) : DataReader {
    private val injectedReaders by lazy {
        inspectStreamStart() // will be initialized on first read
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val injected = injectedReaders
            .firstOrNull { !it.isExhausted }
            ?.read(buffer, offset, length)

        return injected ?: upstream.read(buffer, offset, length)
    }

    private fun inspectStreamStart(): List<BytesReader> {
        val probe = upstream.readExactly(container.startProbeSize)

        return if (container.isStreamStart(probe)) {
            // The stream starts at the file's start: remember it for streams that resume later
            val streamStart = container.reduceStreamStart(
                container.readStreamStart(upstream, probe)
            )
            cache.publish(streamStart)
            onStreamStartCaptured(streamStart, container.streamDurationSeconds(streamStart))
            listOf(BytesReader(streamStart))
        } else {
            // The stream resumes inside the file: replay the stream start in front of it
            val streamStart = cache.await(onStreamStartNeeded, STREAM_START_LOOKUP_TIMEOUT)
                ?: throw IOException("Trying to seek without metadata")
            listOf(
                BytesReader(streamStart),
                BytesReader(container.readResumeStart(upstream, probe))
            )
        }
    }

    private class BytesReader(private val data: ByteArray) : DataReader {
        private var position = 0
        val isExhausted get() = position == data.size

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (isExhausted) {
                return C.RESULT_END_OF_INPUT
            }
            val bytesToCopy = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + bytesToCopy)
            position += bytesToCopy
            return bytesToCopy
        }
    }

    companion object {
        // The stream start is loaded while the loader waits: at the start of the service the
        // lookup is done as soon as the server tells us which track the stream belongs to, which
        // can take a moment when the connection was just established.
        private val STREAM_START_LOOKUP_TIMEOUT = 3.seconds
    }
}

/**
 * Holds the start of the stream the player currently reads, so [StreamPrefixCachingDataReader]
 * can replay it when the server resumes inside the same file.
 */
class StreamStartCache {
    private val lock = Object()

    /** The start of the stream the player reads, as far as it is known. */
    @Volatile
    var streamStartBytes: ByteArray? = null
        private set

    /**
     * Waits for the stream start [load] was asked to deliver; null when it is known that it will
     * not arrive. A stream that resumes inside a file does not contain its start, and without it
     * the extractor cannot decode anything - failing playback over a few moments of waiting would
     * only make the server restart its stream, which lacks the start just the same.
     */
    fun await(load: () -> Boolean, timeout: Duration): ByteArray? {
        streamStartBytes?.let { return it }
        if (!load()) {
            return null
        }
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + timeout.inWholeMilliseconds
            while (streamStartBytes == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    return null
                }
                lock.wait(remaining)
            }
            return streamStartBytes
        }
    }

    fun publish(streamStart: ByteArray) {
        synchronized(lock) {
            streamStartBytes = streamStart
            lock.notifyAll()
        }
    }
}
