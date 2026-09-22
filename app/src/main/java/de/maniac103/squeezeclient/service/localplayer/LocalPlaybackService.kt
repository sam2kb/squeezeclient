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

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import de.maniac103.squeezeclient.Diag
import de.maniac103.squeezeclient.R
import de.maniac103.squeezeclient.extfuncs.connectionHelper
import de.maniac103.squeezeclient.extfuncs.getOrCreateNotificationChannel
import de.maniac103.squeezeclient.extfuncs.localPlayerEnabled
import de.maniac103.squeezeclient.extfuncs.localPlayerName
import de.maniac103.squeezeclient.extfuncs.prefs
import de.maniac103.squeezeclient.extfuncs.putLocalPlayerName
import de.maniac103.squeezeclient.extfuncs.workManager
import de.maniac103.squeezeclient.service.NotificationIds
import de.maniac103.squeezeclient.service.mediasession.MediaService
import de.maniac103.squeezeclient.ui.MainActivity
import de.maniac103.squeezeclient.ui.prefs.SettingsActivity
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response

@OptIn(ExperimentalTime::class)
class LocalPlaybackService :
    Service(),
    LifecycleOwner {
    private val dispatcher = ServiceLifecycleDispatcher(this)
    private lateinit var slimproto: SlimprotoSocket
    private lateinit var player: LocalPlayer
    private lateinit var streamStartStore: StreamPrefixStore

    private var startupTimestampNanos = 0L

    override val lifecycle get() = dispatcher.lifecycle

    private var slimprotoJob: Job? = null
    private var stateListenerJob: Job? = null
    private var statusUpdateJob: Job? = null
    private val slimprotoStateFlow = MutableStateFlow<SlimprotoState>(SlimprotoState.Disconnected)

    private var sentTrackStartStatus = false
    private var sentBufferReady = false
    private var currentStreamUri: String? = null

    /** Position in the track at which the current stream started. */
    private var streamStartPosition = Duration.ZERO
    private var streamInterrupted = false

    /** Set while we asked the server to continue our stream at our position. */
    private var handOffPending = false

    /** Elapsed realtime until which a stream restarted by the server still continues our stream. */
    private var continuationUntil = 0L

    /** Whether the next stream the server starts resumes the track we are paused in. */
    private var streamResumesAfterPause = false

    /** Elapsed realtime at which the current stream started. */
    private var streamStartRealtime = 0L

    /** Our position when it was last computed, and when that happened. */
    private var lastPlayerPosition = Duration.ZERO
    private var lastPlayerPositionRealtime = 0L

    /** The song the position currently refers to. */
    private var lastSongGeneration = 0

    /** Whether the stream start of the stream we play is currently being looked up. */
    private var streamStartLoadActive = false

    /** Set when looking the stream start up for the current stream failed; do not retry it. */
    private var streamStartLoadFailed = false

    /** Stream start captured for a stream whose track is not identified yet. */
    private var pendingStreamStart: PendingStreamStart? = null

    /** The job that stores [pendingStreamStart] once the server can be queried. */
    private var streamStartStoreJob: Job? = null

    /** A stream start waiting for the server to tell which track it belongs to. */
    private data class PendingStreamStart(
        val bytes: ByteArray,
        val duration: Double?,
        val fromPreloadStream: Boolean
    )
    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        super.onCreate()
        slimproto = SlimprotoSocket(prefs)
        streamStartStore = StreamPrefixStore(this)
        player = LocalPlayer(
            this,
            onPlaybackReady = { buffering -> onPlaybackReady(buffering) },
            onPlaybackAdvancedToNextTrack = { onPlaybackAdvancedToNextTrack() },
            onPlaybackEnded = { streamEnded -> onPlaybackEnded(streamEnded) },
            onPlaybackError = { onPlaybackError() },
            onPauseStateChanged = { paused -> onPauseStateChanged(paused) },
            onAudioStreamFlushed = { onAudioStreamFlushed() },
            onDecoderLoadFinished = { onDecoderLoadFinished() },
            onDecodingFinished = { onDecodingFinished() },
            onHeadersReceived = { resp -> onHeadersReceived(resp) },
            onMetadataReceived = { title, artworkUri -> onMetadataReceived(title, artworkUri) },
            onStreamStartNeeded = { provideStreamStart() },
            onStreamStartCaptured = { streamStart, duration, fromPreloadStream ->
                storeStreamStart(streamStart, duration, fromPreloadStream)
            }
        )
        startupTimestampNanos = System.nanoTime()
    }

    override fun onBind(intent: Intent?): IBinder? {
        dispatcher.onServicePreSuperOnBind()
        return null
    }

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dispatcher.onServicePreSuperOnStart()

        if (slimprotoJob == null) {
            slimprotoJob = connectAndRunSlimproto().also {
                it.invokeOnCompletion { slimprotoJob = null }
            }
        }

        if (stateListenerJob == null) {
            stateListenerJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    slimprotoStateFlow
                        .debounce(500.milliseconds)
                        .collectLatest { state ->
                            updateForegroundNotification(state)
                            if (state is SlimprotoState.PlayingOrPaused && !state.paused) {
                                MediaService.start(
                                    this@LocalPlaybackService,
                                    slimproto.playerId,
                                    false
                                )
                            }
                        }
                }
            }
        }

        return START_STICKY
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDestroy() {
        dispatcher.onServicePreSuperOnDestroy()
        GlobalScope.launch {
            slimproto.disconnect()
        }
        player.stop()
        LocalPlayerPosition.clear()
        super.onDestroy()
    }

    private fun onHeadersReceived(response: Response) = lifecycleScope.launch {
        slimproto.sendResponseReceived(response)
    }

    private fun onMetadataReceived(title: CharSequence, artworkUri: Uri?) = lifecycleScope.launch {
        slimproto.sendMediaMetaData(title, artworkUri)
        if (player.readyForPlaybackOrBuffering) {
            slimprotoStateFlow.emit(SlimprotoState.PlayingOrPaused(title, player.paused))
        }
    }

    private fun onPlaybackReady(buffering: Boolean) = lifecycleScope.launch {
        slimprotoStateFlow.emit(
            SlimprotoState.PlayingOrPaused(player.playingTitle, player.paused)
        )
        if (buffering && sentTrackStartStatus) {
            sendStatus(SlimprotoSocket.StatusType.OutputUnderrun)
            sentBufferReady = false
        } else if (!buffering) {
            if (!sentBufferReady) {
                sendStatus(SlimprotoSocket.StatusType.BufferReady)
                sentBufferReady = true
            }
            if (!player.paused) {
                handlePlaybackStart()
            }
        }
    }

    private fun onPlaybackAdvancedToNextTrack() = lifecycleScope.launch {
        sendStatus(SlimprotoSocket.StatusType.TrackStarted)
        sentTrackStartStatus = true
    }

    private fun onPauseStateChanged(paused: Boolean) = lifecycleScope.launch {
        slimprotoStateFlow.emit(
            SlimprotoState.PlayingOrPaused(player.playingTitle, paused)
        )
        if (!paused) {
            handlePlaybackStart()
        }
    }

    private fun onPlaybackEnded(streamEnded: Boolean) = lifecycleScope.launch {
        if (streamEnded && !songHasEnded()) {
            // The stream ended before the song did - the connection died, and the server will
            // restart the stream (at the position it deduced in the meantime) once the player
            // is back. That restart then continues our playback.
            streamInterrupted = true
        }
        slimprotoStateFlow.emit(SlimprotoState.Stopped)
        sentTrackStartStatus = false
        sentBufferReady = false
        if (streamEnded) {
            sendStatus(SlimprotoSocket.StatusType.DecoderUnderrun)
        }
    }

    private fun onPlaybackError() = lifecycleScope.launch {
        streamInterrupted = true
        sentTrackStartStatus = false
        sendStatus(SlimprotoSocket.StatusType.StreamingFailed)
    }

    private fun onAudioStreamFlushed() = lifecycleScope.launch {
        sendStatus(SlimprotoSocket.StatusType.AudioFlushed)
    }

    private fun onDecoderLoadFinished() = lifecycleScope.launch {
        slimproto.sendDisconnect()
    }

    private fun onDecodingFinished() = lifecycleScope.launch {
        sendStatus(SlimprotoSocket.StatusType.DecoderUnderrun)
    }

    /** Whether the song we play has reached its end, as far as its duration is known. */
    private fun songHasEnded(): Boolean {
        val duration = LocalPlayerPosition.songDuration ?: return true
        return playerPosition() >= duration - STREAM_END_TOLERANCE
    }

    private suspend fun handlePlaybackStart() {
        if (!sentTrackStartStatus) {
            sendStatus(SlimprotoSocket.StatusType.TrackStarted)
            sentTrackStartStatus = true
        }
    }

    private fun connectAndRunSlimproto() = lifecycleScope.launch {
        slimprotoStateFlow.emit(SlimprotoState.Disconnected)
        while (isActive) {
            var connected = false
            // Attempt server connection
            while (isActive && !connected) {
                connected = slimproto.connect()
                if (!connected) {
                    delay(10.seconds)
                }
            }

            // We're connected, send notification and start reading commands
            slimprotoStateFlow.emit(SlimprotoState.Stopped)
            while (isActive && connected) {
                slimproto.readNextCommand()
                    .onSuccess { handleCommand(it) }
                    .onFailure {
                        Log.d(TAG, "Could not read command from socket", it)
                        streamInterrupted = true
                        connected = slimproto.reconnect()
                    }
            }
            statusUpdateJob?.cancel()
            slimprotoStateFlow.emit(SlimprotoState.Disconnected)
        }
    }

    @SuppressLint("InlinedApi")
    private fun updateForegroundNotification(state: SlimprotoState) {
        Log.d(TAG, "update notification with state $state")

        val channel = NotificationManagerCompat.from(this)
            .getOrCreateNotificationChannel(resources, NotificationIds.CHANNEL_LOCAL_PLAYBACK)

        val (title, content) = when (state) {
            is SlimprotoState.Disconnected -> Pair(
                getString(R.string.local_player_notification_title_disconnected),
                getString(
                    R.string.local_player_notification_content_disconnected,
                    slimproto.host
                )
            )

            is SlimprotoState.PlayingOrPaused if !state.paused && state.title != null ->
                Pair(
                    getString(R.string.local_player_notification_title_playing),
                    getString(R.string.local_player_notification_content_playing_title, state.title)
                )

            is SlimprotoState.PlayingOrPaused if !state.paused ->
                Pair(
                    getString(R.string.local_player_notification_title_playing),
                    getString(R.string.local_player_notification_content_playing)
                )

            else -> Pair(
                getString(R.string.local_player_notification_title_stopped),
                getString(R.string.local_player_notification_content_stopped)
            )
        }

        val contentIntentStack = arrayOf(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            Intent(this, SettingsActivity::class.java)
        )
        val contentIntent = PendingIntentCompat.getActivities(
            this,
            0,
            contentIntentStack,
            0,
            false
        )
        val notification = NotificationCompat.Builder(this, channel.id)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setSmallIcon(R.drawable.ic_logo_notification_24dp)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NotificationIds.LOCAL_PLAYBACK,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun playerPosition(nowNanos: Long = System.nanoTime()): Duration {
        val raw = LocalPlayerPosition.clamp(
            streamStartPosition + player.determinePlaybackPosition(nowNanos)
        )
        val now = SystemClock.elapsedRealtime()
        if (lastPlayerPositionRealtime == 0L) {
            lastPlayerPosition = raw
            lastPlayerPositionRealtime = now
            return raw
        }
        // The player's position can jump right at a stream flush, as the audio track's timestamp
        // then still refers to what was flushed. A position cannot advance faster than real
        // time, so such a jump is clamped to the time that really passed.
        val elapsed = (now - lastPlayerPositionRealtime).milliseconds
        val position = if (raw > lastPlayerPosition + elapsed + POSITION_JUMP_TOLERANCE) {
            lastPlayerPosition + elapsed
        } else {
            raw
        }
        lastPlayerPosition = position
        lastPlayerPositionRealtime = now
        return position
    }

    /** Time since the server started the current stream, i.e. our position in it. */
    private fun playedSinceStreamStart() =
        (SystemClock.elapsedRealtime() - streamStartRealtime).milliseconds

    /**
     * The song we play can change inside a stream: the server appends the next song to it when it
     * plays gaplessly, and it does so when it considers the current song finished. Count from the
     * new song's beginning then, instead of carrying on the previous song's position.
     */
    private fun checkSongChanged() {
        val generation = LocalPlayerPosition.songGeneration
        if (generation == lastSongGeneration) {
            return
        }
        lastSongGeneration = generation
        val played = player.determinePlaybackPosition(System.nanoTime())
        streamStartPosition = -played
        lastPlayerPositionRealtime = 0
        Diag.log("local", "song changed, counting from 0 again")
    }

    /** Counts the streams the server started, so delayed work can check it still applies. */
    private var streamGeneration = 0

    /**
     * The server restarts a stream at the position it chose. When that position is inside the
     * song (it restores a session, or its stream resumes somewhere in the middle), adopt it so
     * the position follows the audio; a stream that starts a song from its beginning is left
     * alone, as its position is zero like ours. The status is read after a short delay, because
     * right after the restart it still describes the stream we came from.
     */
    private fun checkServerPositionAfterRestart(previousPosition: Duration) =
        lifecycleScope.launch {
            val generation = streamGeneration
            delay(RESTART_STATUS_DELAY)
            if (generation != streamGeneration) {
                return@launch // Another stream started in the meantime
            }
            val serverPosition = fetchServerPosition() ?: return@launch
            val played = playedSinceStreamStart()
            if (serverPosition <= played.inWholeSeconds + ADOPT_MIN_DIFFERENCE.inWholeSeconds) {
                return@launch // The server starts the song at its beginning, nothing to adopt
            }
            streamStartPosition = serverPosition.seconds - played
            lastPlayerPositionRealtime = 0
            Diag.log(
                "local",
                "adopted server position: server=$serverPosition played=$played " +
                    "was=$previousPosition base=$streamStartPosition"
            )
        }

    /**
     * Asks the server to continue its stream at our position, so playback picks up where the user
     * actually was. Retried, as the position query needs CometD, which is still down right after
     * a connection loss.
     */
    private fun handOffPositionToServer(restartPosition: Duration) = lifecycleScope.launch {
        val generation = streamGeneration
        val startedAt = SystemClock.elapsedRealtime()
        repeat(HAND_OFF_ATTEMPTS) {
            if (generation != streamGeneration) {
                return@launch // Another stream started in the meantime
            }
            // The player's timeline is reset asynchronously when the new stream starts, so
            // compute our position from the elapsed realtime instead of asking the player.
            val ourPosition =
                restartPosition + (SystemClock.elapsedRealtime() - startedAt).milliseconds
            val serverPosition = fetchServerPosition()
            if (serverPosition != null) {
                val difference = serverPosition - ourPosition.inWholeSeconds
                Diag.log(
                    "local",
                    "hand-off check: server=$serverPosition ours=$ourPosition " +
                        "difference=$difference"
                )
                if (difference.absoluteValue < HAND_OFF_MIN_DIFFERENCE.inWholeSeconds) {
                    Diag.log("local", "position already matches, no hand-off needed")
                    return@launch
                }
                if (difference.absoluteValue > HAND_OFF_MAX_DIFFERENCE.inWholeSeconds) {
                    Diag.log("local", "position difference too large, keeping our position")
                    return@launch
                }
                Diag.log("local", "asking the server to continue at $ourPosition")
                continuationUntil =
                    SystemClock.elapsedRealtime() + SEEK_RESTART_WINDOW.inWholeMilliseconds
                // The stream the server starts for this request continues ours, so it must not
                // be mistaken for a stream the server started on its own.
                handOffPending = true
                // A connection loss right here must not take the app down; the next
                // interruption tries again.
                runCatching {
                    connectionHelper.updatePlaybackPosition(
                        slimproto.playerId,
                        ourPosition.inWholeSeconds.toInt()
                    )
                }
                // Our own request must not make the restart look server-controlled.
                PositionChangeRequests.clear()
                return@launch
            }
            delay(HAND_OFF_RETRY_DELAY)
        }
        Diag.log("local", "could not reach the server for the position hand-off")
    }

    private suspend fun fetchServerPosition(): Int? {
        val position = connectionHelper.fetchPlaybackPositionSeconds(slimproto.playerId)
        if (position == null) {
            Diag.log("local", "could not fetch the server's position")
        }
        return position
    }

    /**
     * A stream that resumes somewhere inside a track does not contain the start of that track's
     * file, and containers that keep what a decoder needs there (FLAC, Ogg) cannot be decoded
     * without it (see [StreamContainer]). Playback would then fail, which makes the server stop
     * and restart the stream (lacking the start just the same), so hand the player the stream
     * start that was stored when that track started.
     *
     * @return whether the player should wait for the stream start to arrive
     */
    private fun provideStreamStart(): Boolean {
        if (streamStartLoadActive) {
            return true // The stream start of the lookup in flight will land in the cache
        }
        if (streamStartLoadFailed) {
            return false // Not worth waiting again; a new stream gets a new attempt
        }
        streamStartLoadActive = true
        lifecycleScope.launch {
            try {
                // The request needs CometD, which is still connecting right after an app start,
                // so retry as long as the player waits for the stream start.
                repeat(TRACK_QUERY_ATTEMPTS) {
                    val trackResult = runCatching {
                        connectionHelper.getCurrentTrackInfo(slimproto.playerId)
                    }
                    val trackId = trackResult.getOrNull()?.id
                    if (trackId != null) {
                        val streamStart = withContext(Dispatchers.IO) {
                            streamStartStore.load(trackId)
                        }
                        if (streamStart != null) {
                            Diag.log("local", "using the stored stream start for our stream")
                            player.publishStreamStart(streamStart)
                        } else {
                            Diag.log("local", "no stored stream start for our stream")
                            streamStartLoadFailed = true
                        }
                        return@launch
                    }
                    delay(TRACK_QUERY_RETRY_DELAY)
                }
                Diag.log("local", "could not ask the server for the track to load the start for")
            } finally {
                streamStartLoadActive = false
            }
        }
        return true
    }

    /**
     * Stores the start of a stream that began at the start of a file for the track it belongs to,
     * so it is available when that track is resumed without it.
     *
     * The player also reads the stream of the next track early (for gapless playback), and the
     * server may not report that one as current yet: such a stream is only stored when the
     * container states the duration of its file, which identifies the track it belongs to. The
     * same holds for the stream of the track that plays, as the server can be one status ahead.
     */
    private fun storeStreamStart(
        streamStart: ByteArray,
        duration: Double?,
        fromPreloadStream: Boolean
    ) {
        pendingStreamStart = PendingStreamStart(streamStart, duration, fromPreloadStream)
        if (streamStartStoreJob?.isActive == true) {
            return // The running attempt picks the new stream start up
        }
        streamStartStoreJob = lifecycleScope.launch {
            // The request needs CometD, which is still connecting right after an app start
            repeat(TRACK_QUERY_ATTEMPTS) {
                val pending = pendingStreamStart ?: return@launch
                val trackResult = runCatching {
                    connectionHelper.getCurrentTrackInfo(slimproto.playerId)
                }
                val track = trackResult.getOrNull()
                if (track?.id != null) {
                    val durationMatches = pending.duration != null && track.duration != null &&
                        (pending.duration - track.duration).absoluteValue <=
                        STREAM_START_DURATION_TOLERANCE
                    // A stream the server did not start for us can still belong to the track it
                    // plays when the container does not state a duration to compare.
                    val belongsToCurrentTrack = durationMatches ||
                        (pending.duration == null && !pending.fromPreloadStream)
                    if (belongsToCurrentTrack) {
                        withContext(Dispatchers.IO) {
                            streamStartStore.store(track.id, pending.bytes)
                        }
                        Diag.log("local", "stored the stream start of the track we play")
                    } else {
                        Diag.log("local", "not storing the stream start: it is for another track")
                    }
                    pendingStreamStart = null
                    return@launch
                }
                delay(TRACK_QUERY_RETRY_DELAY)
            }
            pendingStreamStart = null
            Diag.log("local", "could not ask the server which track to store the stream start for")
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun sendStatus(type: SlimprotoSocket.StatusType) {
        val nowNanos = System.nanoTime()
        val elapsed = (nowNanos - startupTimestampNanos).toDuration(DurationUnit.NANOSECONDS)
        val (bufferFullness, bufferSize) = player.estimateBufferFullnessAndSize()
        checkSongChanged()
        val position = playerPosition(nowNanos)
        LocalPlayerPosition.update(slimproto.playerId, position)
        Diag.log(
            "local",
            "status=$type position=$position buffered=$bufferFullness/$bufferSize " +
                "ready=${player.readyForPlayback} playing=${player.isPlaying}"
        )

        slimproto.sendStatus(
            type,
            elapsed,
            player.readyForPlayback,
            position,
            player.totalTransferredBytes,
            bufferFullness,
            bufferSize
        )

        // Make sure we send an update at least once per second while playing
        statusUpdateJob?.cancel()
        if (player.isPlaying) {
            statusUpdateJob = lifecycleScope.launch {
                delay(1.seconds)
                sendStatus(SlimprotoSocket.StatusType.Timer(0))
            }
        }
    }

    private suspend fun handleCommand(command: SlimprotoSocket.CommandPacket) {
        when (command) {
            is SlimprotoSocket.CommandPacket.AudioEnable -> {}

            is SlimprotoSocket.CommandPacket.AudioGain -> {
                // TODO: digital volume control?
                player.volume = (command.left + command.right) / 2
            }

            is SlimprotoSocket.CommandPacket.Continue -> {
                if (player.readyForPlaybackOrBuffering) {
                    player.paused = false
                }
            }

            is SlimprotoSocket.CommandPacket.StreamStart -> {
                val previousPosition = playerPosition()
                // The position we asked the server for, when this stream start is the consequence
                // of that request: it is where the stream plays, unlike the server's own position
                // (see PositionChangeRequests).
                val requestedPosition = PositionChangeRequests.requestedPosition()
                // The server restarts its stream on a connection loss; that continues our
                // stream, so keep counting from our position instead of restarting at zero.
                // A stream the server starts right after we asked for a different position or
                // track plays what the server chose - everything else continues our track.
                val handOffDue = handOffPending &&
                    SystemClock.elapsedRealtime() < continuationUntil
                val streamContinues = handOffDue || streamInterrupted || streamResumesAfterPause ||
                    SystemClock.elapsedRealtime() < continuationUntil
                val continuesCurrentStream = (!PositionChangeRequests.isRecent() || handOffDue) &&
                    command.uri.toString() == currentStreamUri && streamContinues
                // The server resumes at the position it deduced itself, which can be ahead of
                // what we played: it assumes everything it sent was consumed, and it keeps
                // counting while we are not connected. A stream that continues an interrupted
                // one, or one that resumes after a pause, therefore always gets our position -
                // even when a position request happened right before, as its stream may have
                // started from a position the server got wrong in the meantime.
                val handOffPosition = streamInterrupted || streamResumesAfterPause
                Diag.log(
                    "local",
                    "strm-s uri=${command.uri} autoStart=${command.autoStart} " +
                        "direct=${command.directStreaming} position=$previousPosition " +
                        "continues=$continuesCurrentStream handOff=$handOffPosition " +
                        "requested=$requestedPosition"
                )
                // Server-initiated restarts can arrive in bursts; keep treating them as one
                // continuation, so the position stays continuous across the whole burst.
                continuationUntil = if (continuesCurrentStream) {
                    SystemClock.elapsedRealtime() + SEEK_RESTART_WINDOW.inWholeMilliseconds
                } else {
                    0
                }
                streamInterrupted = false
                streamResumesAfterPause = false
                handOffPending = false
                streamStartLoadFailed = false
                sendStatus(SlimprotoSocket.StatusType.Connecting)
                currentStreamUri = command.uri.toString()
                streamGeneration++
                streamStartRealtime = SystemClock.elapsedRealtime()
                // The player's timeline starts at zero with the new stream, so our position in
                // the restarted stream simply is where we were when it ended - or the position
                // we asked the server for, when this stream is the answer to that request.
                streamStartPosition = when {
                    continuesCurrentStream -> previousPosition
                    requestedPosition != null -> requestedPosition.seconds
                    else -> Duration.ZERO
                }
                lastPlayerPositionRealtime = 0
                if (handOffPosition) {
                    handOffPositionToServer(previousPosition)
                } else if (!continuesCurrentStream && requestedPosition == null) {
                    checkServerPositionAfterRestart(previousPosition)
                }
                sentTrackStartStatus = false // new strm-s requires new STMs to be sent
                sentBufferReady = command.autoStart
                player.play(
                    command.uri,
                    command.mimeType,
                    command.headers,
                    command.replayGain,
                    // In direct streaming case we need to wait for the
                    // continue packet before starting playback
                    command.autoStart && !command.directStreaming,
                    // A restarted stream replaces the stream we play; a stream for the next
                    // playlist item is appended so it can be pre-buffered for gapless playback.
                    continuesCurrentStream
                )
            }

            is SlimprotoSocket.CommandPacket.StreamPause -> {
                Diag.log("local", "strm-p position=${playerPosition()}")
                streamResumesAfterPause = true
                player.paused = true
                if (command.pauseInterval != null) {
                    delay(command.pauseInterval)
                    player.paused = false
                } else {
                    sendStatus(SlimprotoSocket.StatusType.StreamingPaused)
                }
            }

            is SlimprotoSocket.CommandPacket.StreamUnpause -> {
                streamResumesAfterPause = true
                val uptime = (System.nanoTime() - startupTimestampNanos)
                    .toDuration(DurationUnit.NANOSECONDS)
                val unpauseDelay = command.unpauseTimestamp - uptime
                Diag.log(
                    "local",
                    "strm-u delay=$unpauseDelay position=${playerPosition()}"
                )
                if (unpauseDelay.isPositive()) {
                    Log.d(TAG, "Delaying unpause for $unpauseDelay ms")
                    delay(unpauseDelay)
                }
                sentBufferReady = true
                player.paused = false
                sendStatus(SlimprotoSocket.StatusType.StreamingResumed)
            }

            is SlimprotoSocket.CommandPacket.StreamSkipAhead -> {
                Diag.log(
                    "local",
                    "strm-a skip=${command.skipOverInterval} position=${playerPosition()}"
                )
                player.skipAhead(command.skipOverInterval)
            }

            is SlimprotoSocket.CommandPacket.StreamStop -> {
                Diag.log("local", "strm-t position=${playerPosition()}")
                player.stop()
            }

            is SlimprotoSocket.CommandPacket.StreamStatus -> {
                sendStatus(SlimprotoSocket.StatusType.Timer(command.timestamp))
            }

            is SlimprotoSocket.CommandPacket.TriggerGetSetting -> {
                if (command.type == SlimprotoSocket.SettingType.PlayerName) {
                    // Trim \0 characters from saved setting that were written before
                    // the trimming code below was added
                    val name = prefs.localPlayerName?.trim('\u0000')
                        ?: "SqueezeClient - ${Build.MODEL}"
                    slimproto.sendSetSetting(command.type, name.toByteArray(Charsets.US_ASCII))
                }
            }

            is SlimprotoSocket.CommandPacket.SetSetting -> {
                if (command.type == SlimprotoSocket.SettingType.PlayerName) {
                    // The server sends the name 0 terminated; remove the termination before saving
                    val name = String(command.data).trim('\u0000')
                    Log.d(TAG, "Changing local player name to $name")
                    prefs.edit {
                        putLocalPlayerName(name)
                    }
                }
            }

            else -> {
                Log.d(TAG, "unhandled command $command")
            }
        }
    }

    sealed class SlimprotoState {
        data object Disconnected : SlimprotoState()
        data object Stopped : SlimprotoState()
        data class PlayingOrPaused(val title: CharSequence?, val paused: Boolean) : SlimprotoState()
    }

    companion object {
        private const val TAG = "LocalPlaybackService"

        /** Position difference below which the server's position is left alone. */
        private val HAND_OFF_MIN_DIFFERENCE = 5.seconds

        /**
         * Maximum difference between our and the server's position that is still handed over. It
         * can legitimately be large: the server pauses its own position when the connection drops
         * while we keep playing from the buffer, so the difference grows with every interruption.
         */
        private val HAND_OFF_MAX_DIFFERENCE = 10.minutes

        /** How often the hand-off is retried when the server cannot be queried yet. */
        private const val HAND_OFF_ATTEMPTS = 10

        /** Delay between two hand-off attempts. */
        private val HAND_OFF_RETRY_DELAY = 2.seconds

        /** Tolerance when matching a stored stream start against the server's track duration. */
        private const val STREAM_START_DURATION_TOLERANCE = 2.0

        /** How often the server is asked for the current track before giving up. */
        private const val TRACK_QUERY_ATTEMPTS = 6

        /** Delay between two attempts to ask the server for the current track. */
        private val TRACK_QUERY_RETRY_DELAY = 500.milliseconds

        /** Time window in which a stream started by the server is still considered a restart. */
        private val SEEK_RESTART_WINDOW = 5.seconds

        /** Delay before the server's position is read after it restarted a stream. */
        private val RESTART_STATUS_DELAY = 2.seconds

        /** Position the server must have advanced into the song for us to adopt it. */
        private val ADOPT_MIN_DIFFERENCE = 2.seconds

        /** Tolerance for deciding whether a finished stream ended the song. */
        private val STREAM_END_TOLERANCE = 2.seconds

        /** How far the position may jump beyond the time that actually passed. */
        private val POSITION_JUMP_TOLERANCE = 5.seconds

        fun triggerStartOrStop(context: Context) {
            val serviceIntent = Intent(context, LocalPlaybackService::class.java)
            when {
                !context.prefs.localPlayerEnabled -> {
                    // Local player disabled -> service not needed
                    context.stopService(serviceIntent)
                }

                Build.VERSION.SDK_INT <= Build.VERSION_CODES.R -> {
                    // Avoid using the startup worker on Android 11 and older:
                    // - The restrictions mentioned below are not applicable there
                    // - Android 11 and older require getForegroundInfo() in the worker when
                    //   calling setExpedited() in the work request (see [1]), but we don't
                    //   want to show a notification.
                    //   [1] https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#backwards-compat
                    context.startForegroundService(serviceIntent)
                }

                else -> {
                    // On Android 12 and newer, use the startup worker:
                    // - Since Android 12 apps are not allowed to start foreground services from
                    //   background, see
                    //   https://developer.android.com/guide/components/foreground-services#background-start-restrictions
                    // - While our use case (startup from boot completed) was listed as exempted
                    //   there, it no longer is since Android 15, see
                    //   https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
                    val request = OneTimeWorkRequestBuilder<LocalPlayerStartupWorker>()
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                    context.workManager.enqueue(request)
                }
            }
        }
    }
}
