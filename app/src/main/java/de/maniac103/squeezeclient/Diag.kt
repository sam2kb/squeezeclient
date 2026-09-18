/*
 * This file is part of Squeeze Client, an Android client for the LMS music server.
 * Copyright (c) 2024 Danny Baumann
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

package de.maniac103.squeezeclient

import android.content.Context
import android.util.Log
import de.maniac103.squeezeclient.model.Playlist
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * TEMPORARY diagnostic logging.
 *
 * Used for tracking down car head unit state sync problems (missing/stale track info, repeated
 * play/pause keys). Everything routes through here, so it can be removed again by deleting this
 * file and dropping all lines that mention `Diag.` (find them with `grep -rn "Diag\." app/src`).
 *
 * Output goes to logcat (tag `SQZDiag`) and to a rotating file, which can be pulled with
 * `adb pull /sdcard/Android/data/de.maniac103.squeezeclient.debug/files/diag.log`.
 */
@OptIn(ExperimentalTime::class)
object Diag {
    private const val TAG = "SQZDiag"
    private const val FILE_NAME = "diag.log"
    private const val MAX_FILE_SIZE = 2L * 1024 * 1024

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiagLog").apply { isDaemon = true }
    }
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, FILE_NAME)
        logFile = file
        log("diag", "=== log start -> ${file.absolutePath} ===")
    }

    fun log(area: String, message: String) {
        val line = "$area: $message"
        Log.d(TAG, line)
        val file = logFile ?: return
        writer.execute {
            try {
                if (file.length() > MAX_FILE_SIZE) {
                    val backup = File(file.parentFile, "$FILE_NAME.1")
                    backup.delete()
                    file.renameTo(backup)
                }
                file.appendText("${dateFormat.format(Date())} $line\n")
            } catch (t: Throwable) {
                Log.w(TAG, "cannot write log file: $t")
            }
        }
    }

    fun song(item: Playlist.PlaylistItem?): String =
        if (item == null) "<none>" else "'${item.title}' [${item.artist}]"

    fun rev(instant: Instant?): String = instant?.toString() ?: "<none>"
}
