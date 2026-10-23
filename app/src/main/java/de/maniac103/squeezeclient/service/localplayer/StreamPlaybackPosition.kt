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

class StreamPlaybackPosition(val id: Int, var offset: Duration) {
    @Volatile
    private var firstTimestamp: Duration? = null

    fun recordFirstTimestamp(timestamp: Duration) {
        if (firstTimestamp == null) firstTimestamp = timestamp
    }

    fun streamPosition(sourcePosition: Duration, absolute: Boolean): Duration = if (absolute) {
        firstTimestamp?.let { (sourcePosition - it).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO
    } else {
        sourcePosition.coerceAtLeast(Duration.ZERO)
    }

    fun position(sourcePosition: Duration, absolute: Boolean) = if (absolute) {
        maxOf(firstTimestamp ?: offset, sourcePosition)
    } else {
        offset + sourcePosition
    }
}
