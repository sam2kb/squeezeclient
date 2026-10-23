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

package de.maniac103.squeezeclient.service.localplayer

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo

@UnstableApi
internal class LocalPlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    @Volatile
    private var minimumRetryCount = DEFAULT_MIN_LOADABLE_RETRY_COUNT

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        minimumRetryCount = super.getMinimumLoadableRetryCount(dataType)
        return Int.MAX_VALUE
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        val networkFailure = exception is HttpDataSourceException && (
            exception.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                exception.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            )
        if (!networkFailure && loadErrorInfo.errorCount > minimumRetryCount) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
