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

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Keeps the start of the streams that were played recently, keyed by their server side track ID.
 * A stream that resumes inside a track does not contain that start, so without it the player
 * cannot decode such a stream (see [StreamPrefixCachingDataReader]) - the store covers the case
 * that the player itself never read the start of that track, e.g. after an app restart while the
 * server kept playing.
 *
 * Only a few entries are kept: the track that plays and the ones played before it. The stream
 * start itself is small (a few hundred bytes once a container's padding is dropped), so no space
 * worth mentioning is used.
 */
class StreamPrefixStore(context: Context) {
    private val directory = File(context.filesDir, "stream-starts")

    /** Stores [streamStart] for [trackId], replacing an entry that is already present. */
    fun store(trackId: Long, streamStart: ByteArray) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) {
                Log.w(TAG, "Could not create directory for stored stream starts")
                return
            }
            val file = File(directory, trackId.toString())
            // Write to a temporary file first, so a killed app cannot leave a partial entry
            val temporaryFile = File(directory, "$trackId.tmp")
            temporaryFile.writeBytes(streamStart)
            if (!temporaryFile.renameTo(file)) {
                temporaryFile.delete()
                Log.w(TAG, "Could not store the stream start for track $trackId")
                return
            }
            file.setLastModified(System.currentTimeMillis())
            removeOldEntries()
        }.onFailure {
            Log.w(TAG, "Storing the stream start for track $trackId failed", it)
        }
    }

    /** The stream start stored for [trackId], or null when nothing is stored for it. */
    fun load(trackId: Long): ByteArray? = runCatching {
        val file = File(directory, trackId.toString())
        if (!file.exists()) {
            return null
        }
        val streamStart = file.readBytes()
        // Keep entries that are still in use from being evicted
        file.setLastModified(System.currentTimeMillis())
        streamStart.takeIf { it.isNotEmpty() }
    }.onFailure {
        Log.w(TAG, "Loading the stream start for track $trackId failed", it)
    }.getOrNull()

    private fun removeOldEntries() {
        val files = directory.listFiles() ?: return
        if (files.size <= MAX_ENTRIES) {
            return
        }
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_ENTRIES)
            .forEach { it.delete() }
    }

    companion object {
        private const val TAG = "StreamPrefixStore"

        /** Number of tracks whose stream start is kept; more would only accumulate stale entries. */
        private const val MAX_ENTRIES = 4
    }
}
