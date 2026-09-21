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

package de.maniac103.squeezeclient.service.mediasession

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import de.maniac103.squeezeclient.Diag
import de.maniac103.squeezeclient.cometd.ConnectionHelper
import de.maniac103.squeezeclient.cometd.request.PlaybackButtonRequest
import de.maniac103.squeezeclient.extfuncs.lastSessionPlayer
import de.maniac103.squeezeclient.extfuncs.lastSessionPosition
import de.maniac103.squeezeclient.extfuncs.lastSessionTimestamp
import de.maniac103.squeezeclient.extfuncs.lastSessionWasPlaying
import de.maniac103.squeezeclient.extfuncs.localPlayerId
import de.maniac103.squeezeclient.extfuncs.prefs
import de.maniac103.squeezeclient.extfuncs.putLastSession
import de.maniac103.squeezeclient.extfuncs.resumePlayback
import de.maniac103.squeezeclient.extfuncs.volumeStepSize
import de.maniac103.squeezeclient.model.PagingParams
import de.maniac103.squeezeclient.model.PlayerId
import de.maniac103.squeezeclient.model.PlayerStatus
import de.maniac103.squeezeclient.model.Playlist
import de.maniac103.squeezeclient.service.localplayer.LocalPlayerPosition
import de.maniac103.squeezeclient.service.localplayer.PositionChangeRequests
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

@UnstableApi
class SqueezeboxMediaPlayer(
    private val appContext: Context,
    private val connectionHelper: ConnectionHelper,
    private val lifecycle: Lifecycle
) : SimpleBasePlayer(Looper.getMainLooper()),
    CoroutineScope by lifecycle.coroutineScope {
    var currentPlayer: PlayerId? = null
        set(value) {
            if (field != value) {
                field = value
                // A new player gets a fresh chance to continue its last session; connection
                // blips for the same player don't (see resumeLastSession).
                resumeAttempted = false
                updatePlayer(value)
            }
        }
    var isConnectedToServer: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                Diag.log("player", "isConnectedToServer=$value")
                updatePlayer(currentPlayer)
                launch {
                    if (!value) {
                        // Don't report loss of connection immediately: reconnection may already
                        // be happening, and we do not want to report intermediate states
                        delay(2.seconds)
                    }
                    invalidateState()
                }
            }
        }
    private var pendingPlayerState = PendingPlayerState()
    private var playerState: PlayerState? = null
    private var unacknowledgedStateChange: UnacknowledgedPlayerStateChange? = null
    private var statusSubscription: Job? = null
    private var playlistFetchJob: Job? = null
    private var delayedStateUpdateJob: Job? = null
    private var unacknowledgedStateRevertJob: Job? = null

    /** Whether the given player is the one built into the app. */
    private fun isLocalPlayer(playerId: PlayerId) = playerId == appContext.prefs.localPlayerId

    private var statusSubscriptionStartTime = 0L
    private var resumeAttempted = false
    private var lastSessionStore = 0L

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int) = future {
        val playerId = currentPlayer ?: return@future
        connectionHelper.setVolume(playerId, deviceVolume)
    }

    override fun handleIncreaseDeviceVolume(flags: Int) = future {
        val playerId = currentPlayer ?: return@future
        val currentVolume = playerState?.currentVolume ?: return@future
        val stepSize = appContext.prefs.volumeStepSize
        connectionHelper.setVolume(playerId, currentVolume + stepSize)
    }

    override fun handleDecreaseDeviceVolume(flags: Int) = future {
        val playerId = currentPlayer ?: return@future
        val currentVolume = playerState?.currentVolume ?: return@future
        val stepSize = appContext.prefs.volumeStepSize
        connectionHelper.setVolume(playerId, currentVolume - stepSize)
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int) = future {
        val playerId = currentPlayer ?: return@future
        connectionHelper.setMuteState(playerId, muted)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = future {
        Diag.log("cmd", "setPlayWhenReady($playWhenReady)")
        val playerId = currentPlayer ?: return@future
        val newState = when {
            playWhenReady -> PlayerStatus.PlayState.Playing
            else -> PlayerStatus.PlayState.Paused
        }
        updateUnacknowledgedState(playState = newState)
        connectionHelper.changePlaybackState(playerId, newState)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = future {
        Diag.log("cmd", "seek cmd=$seekCommand index=$mediaItemIndex posMs=$positionMs")
        val playerId = currentPlayer ?: return@future
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT -> {
                PositionChangeRequests.note()
                updateUnacknowledgedState(
                    playlistPositionOffset = 1,
                    song = songAtOffset(1)
                )
                connectionHelper.sendButtonRequest(PlaybackButtonRequest.NextTrack(playerId))
            }

            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS -> {
                PositionChangeRequests.note()
                updateUnacknowledgedState(
                    playlistPositionOffset = -1,
                    song = songAtOffset(-1)
                )
                connectionHelper.sendButtonRequest(
                    PlaybackButtonRequest.PreviousTrack(playerId)
                )
            }

            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                val positionSeconds = ((positionMs + 500) / 1000).toInt()
                updateUnacknowledgedState(positionInTrack = positionMs.milliseconds)
                connectionHelper.updatePlaybackPosition(playerId, positionSeconds)
            }

            COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val positionSeconds = ((positionMs + 500) / 1000).toInt()
                updateUnacknowledgedState(
                    absolutePlaylistPosition = mediaItemIndex,
                    positionInTrack = positionMs.milliseconds
                )
                connectionHelper.advanceToPlaylistPosition(playerId, mediaItemIndex)
                if (positionSeconds > 0) {
                    connectionHelper.updatePlaybackPosition(playerId, positionSeconds)
                }
            }

            else -> {}
        }
    }

    override fun handleStop() = future {
        Diag.log("cmd", "stop")
        val playerId = currentPlayer ?: return@future
        updateUnacknowledgedState(playState = PlayerStatus.PlayState.Stopped)
        connectionHelper.changePlaybackState(playerId, PlayerStatus.PlayState.Stopped)
    }

    override fun getState(): State {
        val playerState = playerState
            ?: return waitingForPlayerState()
        val currentSong = unacknowledgedStateChange?.song ?: playerState.currentSong
            ?: return State.Builder().setPlaybackState(STATE_IDLE).build()
        val unacknowledgedChange = unacknowledgedStateChange

        val currentSongDurationUs =
            playerState.currentSongDuration?.toLong(DurationUnit.MICROSECONDS)
        val (playlist, currentIndex) = playerState.playlist?.let { list ->
            val currentPosition = when {
                unacknowledgedChange?.absolutePlaylistPosition != null ->
                    unacknowledgedChange.absolutePlaylistPosition

                unacknowledgedChange?.playlistPositionOffset != null ->
                    playerState.playlistPosition + unacknowledgedChange.playlistPositionOffset

                else -> playerState.playlistPosition
            }
            val mediaList: List<MediaItemData> = list.items.mapIndexed { index, item ->
                val builder = if (index + list.offset == currentPosition) {
                    // Prefer current song from status over playlist item, because the former
                    // may be more up to date (e.g. in case of radio streams)
                    currentSong.toMediaItemDataBuilder(index).apply {
                        currentSongDurationUs?.let { setDurationUs(it) }
                    }
                } else {
                    item.toMediaItemDataBuilder(index)
                }
                builder.build()
            }
            Pair(mediaList, currentPosition - list.offset)
        } ?: currentSong.let { song ->
            val builder = song.toMediaItemDataBuilder(0)
            currentSongDurationUs?.let { builder.setDurationUs(it) }
            Pair(listOf(builder.build()), 0)
        }

        val commandsBuilder = Player.Commands.Builder().apply {
            add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            add(COMMAND_GET_METADATA)
            add(COMMAND_GET_TIMELINE)
            add(COMMAND_PLAY_PAUSE)
            if (playerState.currentSongDuration != null &&
                playerState.playbackState != PlayerStatus.PlayState.Stopped
            ) {
                add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
            add(COMMAND_SEEK_TO_MEDIA_ITEM)
            add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            add(COMMAND_SEEK_TO_NEXT)
            add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            add(COMMAND_SEEK_TO_PREVIOUS)
            add(COMMAND_STOP)
            if (playerState.currentVolume != null) {
                add(COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                add(COMMAND_GET_DEVICE_VOLUME)
                add(COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            }
        }

        val playWhenReady = playerState.playbackState == PlayerStatus.PlayState.Playing
        val playbackState = when {
            !isConnectedToServer -> STATE_BUFFERING

            !playerState.powered -> STATE_IDLE

            else -> when (unacknowledgedChange?.playState ?: playerState.playbackState) {
                PlayerStatus.PlayState.Playing -> STATE_READY
                PlayerStatus.PlayState.Paused -> STATE_READY
                PlayerStatus.PlayState.Stopped -> STATE_IDLE
            }
        }

        val builder = State.Builder()
            .setPlaybackState(playbackState)
            .setAvailableCommands(commandsBuilder.build())
            .setContentPositionMs(
                unacknowledgedChange?.positionInTrack?.inWholeMilliseconds
                    ?: LocalPlayerPosition.forPlayer(currentPlayer)
                        ?.toLong(DurationUnit.MILLISECONDS)
                    ?: playerState.currentPlayPosition?.toLong(DurationUnit.MILLISECONDS)
                    ?: C.TIME_UNSET
            )
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex)

        playerState.currentVolume?.let {
            builder.setDeviceVolume(it)
            builder.setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(100)
                    .build()
            )
        }
        playerState.muted?.let { builder.setIsDeviceMuted(it) }

        Diag.log(
            "getState",
            "published=${Diag.song(currentSong)} index=$currentIndex items=${playlist.size} " +
                "pbState=$playbackState playWhenReady=$playWhenReady " +
                "song=${Diag.song(playerState.currentSong)} " +
                "statusSong=${Diag.song(pendingPlayerState.status?.playlist?.nowPlaying)} " +
                "playlistRev=${Diag.rev(playerState.playlist?.timestamp)} " +
                "pending=${Diag.rev(pendingPlayerState.status?.playlist?.lastChange)} " +
                "unack=$unacknowledgedChange"
        )
        return builder.build()
    }

    /**
     * The song a relative playlist move leads to, as far as we know it. A track change should
     * show the new song right away: until the server confirms it, the previous song (whose
     * playlist revision is still current) would otherwise be reported for a moment, which makes
     * head units flip back to it and then forward again.
     */
    private fun songAtOffset(offset: Int): Playlist.PlaylistItem? {
        val state = playerState ?: return null
        val playlist = state.playlist ?: return null
        return playlist.items.getOrNull(state.playlistPosition + offset)
    }

    private fun updateUnacknowledgedState(
        absolutePlaylistPosition: Int? = null,
        playlistPositionOffset: Int? = null,
        positionInTrack: Duration? = null,
        playState: PlayerStatus.PlayState? = null,
        song: Playlist.PlaylistItem? = null
    ) {
        val newPlayState = playState ?: unacknowledgedStateChange?.playState
        val newSong = song ?: unacknowledgedStateChange?.song
        val newPositionInTrack = positionInTrack ?: unacknowledgedStateChange?.positionInTrack
        val newAbsolutePosition = absolutePlaylistPosition
            ?: unacknowledgedStateChange?.absolutePlaylistPosition
        val existingOffset = unacknowledgedStateChange?.playlistPositionOffset
        val newOffset = when {
            // absolute position takes precedence
            newAbsolutePosition != null -> null

            playlistPositionOffset != null && existingOffset != null ->
                existingOffset + playlistPositionOffset

            playlistPositionOffset != null -> playlistPositionOffset

            else -> existingOffset
        }
        val playlistLength = pendingPlayerState.playlist?.totalCount ?: Int.MAX_VALUE

        unacknowledgedStateChange = UnacknowledgedPlayerStateChange(
            newAbsolutePosition
                ?.plus(playlistPositionOffset ?: 0)
                ?.coerceIn(0, playlistLength),
            newOffset?.coerceIn(0, playlistLength),
            newPositionInTrack,
            newPlayState,
            newSong
        )
        Diag.log("unack", "reporting $unacknowledgedStateChange until the server confirms")
        invalidateState()

        unacknowledgedStateRevertJob?.cancel()
        unacknowledgedStateRevertJob = launch {
            // Give the server some reasoanble amount of time to react
            delay(3.seconds)
            unacknowledgedStateChange = null
            invalidateState()
        }
    }

    /**
     * State to report while nothing is known about the controlled player yet (e.g. right
     * after the service has been started). A buffering state with a placeholder item is
     * reported instead of an empty state, because the media session only becomes visible
     * to the system (and the service only gets into the foreground) if it has a timeline
     * with a playing/buffering item.
     */
    private fun waitingForPlayerState(): State {
        val placeholder = MediaItemData.Builder(0)
            .setMediaItem(MediaItem.Builder().setMediaId("waitingForPlayer").build())
            .build()
        return State.Builder()
            .setPlaybackState(STATE_BUFFERING)
            .setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(listOf(placeholder))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun updatePlayer(playerId: PlayerId?) {
        Diag.log("player", "updatePlayer($playerId)")
        statusSubscriptionStartTime = SystemClock.elapsedRealtime()
        statusSubscription?.cancel()
        if (playerId == null || !isConnectedToServer) {
            return
        }
        statusSubscription = launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionHelper.playerState(playerId)
                    .flatMapLatest { it.playStatus }
                    .collect { status -> handlePlayerStatusUpdate(playerId, status) }
            }
        }
    }

    private fun handlePlayerStatusUpdate(playerId: PlayerId, status: PlayerStatus) {
        val latestStatus = pendingPlayerState.status
        val newPlaylistTimestamp = status.playlist.lastChange
        Diag.log(
            "status",
            "recv rev=${Diag.rev(newPlaylistTimestamp)} " +
                "song=${Diag.song(status.playlist.nowPlaying)} " +
                "idx=${status.playlist.currentPosition}/${status.playlist.trackCount} " +
                "state=${status.playbackState} powered=${status.powered} " +
                "vol=${status.currentVolume} muted=${status.muted} " +
                "knownRev=${Diag.rev(latestStatus?.playlist?.lastChange)}"
        )
        if (latestStatus != null && newPlaylistTimestamp < latestStatus.playlist.lastChange) {
            // The new status is older than what we already know about -> ignore it
            val knownRev = Diag.rev(latestStatus.playlist.lastChange)
            Diag.log("status", "-> dropped, older than $knownRev")
            return
        }

        if (status.playlist.lastChange != latestStatus?.playlist?.lastChange) {
            playlistFetchJob?.cancel()
            Diag.log("playlist", "fetch start rev=${Diag.rev(status.playlist.lastChange)}")
            playlistFetchJob = lifecycle.coroutineScope.launch {
                val playlist = connectionHelper.fetchPlaylist(playerId, PagingParams.All)
                Diag.log(
                    "playlist",
                    "fetch done rev=${Diag.rev(playlist.timestamp)} " +
                        "items=${playlist.items.size} index=${playlist.currentIndex} " +
                        "wanted=${Diag.rev(status.playlist.lastChange)}"
                )
                if (playlist.timestamp == status.playlist.lastChange) {
                    pendingPlayerState.playlist = playlist
                    // Player state update is scheduled asynchronously so that the update method
                    // notices the playlist fetch being done
                    lifecycle.coroutineScope.launch {
                        schedulePlayerStateUpdate()
                    }
                }
            }
        }

        pendingPlayerState.status = status
        schedulePlayerStateUpdate()
    }

    private fun schedulePlayerStateUpdate() {
        delayedStateUpdateJob?.cancel()

        val status = pendingPlayerState.status ?: return
        val nowPlaying = playerState?.currentSong
        val newPlayerState = PlayerState(status, nowPlaying, pendingPlayerState.playlist)

        when {
            newPlayerState.isCompleteAndConsistent() || playerState == null -> {
                // Accept immediately if either
                // - we don't have a state yet (don't wait for playlist)
                // - or what we have looks consistent
                Diag.log(
                    "state",
                    "apply immediately (consistent=${newPlayerState.isCompleteAndConsistent()})"
                )
                applyPlayerState(newPlayerState)
            }

            playlistFetchJob?.isActive == true -> {
                // Playlist is currently being fetched; we'll come here again once that is done
                Diag.log("state", "waiting for the playlist fetch")
            }

            else -> {
                // Give the server a little more time for sending us consistent data:
                // When we come here we have received a status update, fetched the playlist and
                // the timestamps don't match. That means we'll likely get another status report
                // which will trigger another playlist fetch.
                Diag.log("state", "delaying apply by 500 ms (inconsistent data)")
                delayedStateUpdateJob = lifecycle.coroutineScope.launch {
                    delay(500.milliseconds)
                    applyPlayerState(newPlayerState)
                }
            }
        }
    }

    private fun applyPlayerState(newPlayerState: PlayerState) {
        LocalPlayerPosition.updateDuration(newPlayerState.currentSongDuration)
        val previousSong = playerState?.currentSong
        if (previousSong != null && previousSong != newPlayerState.currentSong) {
            LocalPlayerPosition.noteSongChanged()
        }
        Diag.log(
            "state",
            "applied song=${Diag.song(newPlayerState.currentSong)} " +
                "idx=${newPlayerState.playlistPosition} " +
                "state=${newPlayerState.playbackState} " +
                "playlistRev=${Diag.rev(newPlayerState.playlist?.timestamp)}"
        )
        val pendingSong = unacknowledgedStateChange?.song
        playerState = newPlayerState
        // Keep reporting the pending song until the server's state shows it; statuses for the
        // still-current previous revision arrive in between and would flip the metadata back.
        if (pendingSong == null || newPlayerState.currentSong == pendingSong) {
            unacknowledgedStateChange = null
            unacknowledgedStateRevertJob?.cancel()
        }
        invalidateState()
        rememberSession(newPlayerState)
        resumeLastSession(newPlayerState)
    }

    /**
     * Remembers where playback was left off, so an interrupted session can be continued (see
     * [resumeLastSession]).
     */
    private fun rememberSession(state: PlayerState) {
        val playerId = currentPlayer ?: return
        if (!isLocalPlayer(playerId)) {
            return
        }
        if (state.playbackState == PlayerStatus.PlayState.Playing) {
            // Something is playing in this app run, so there is nothing to resume anymore
            resumeAttempted = true
            if (!sessionStoreDue()) {
                return
            }
            val position = state.currentPlayPosition?.inWholeSeconds?.toInt() ?: 0
            appContext.prefs.edit { putLastSession(playerId, position, true) }
            return
        }
        // Ignore what the server reports right after (re)connecting: it may still report the
        // player as stopped before its previous state has been restored.
        val settled = SystemClock.elapsedRealtime() - statusSubscriptionStartTime >
            SESSION_SETTLE_TIME.inWholeMilliseconds
        if (!settled) {
            return
        }
        if (!sessionStoreDue()) {
            return
        }
        val position = state.currentPlayPosition?.inWholeSeconds?.toInt() ?: 0
        appContext.prefs.edit { putLastSession(playerId, position, false) }
    }

    /** Whether the session should be remembered now; state updates arrive several times/s. */
    private fun sessionStoreDue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSessionStore < SESSION_STORE_INTERVAL.inWholeMilliseconds) {
            return false
        }
        lastSessionStore = now
        return true
    }

    /**
     * Continues the session that was interrupted by the app being stopped or by the connection to
     * the server going away, at the position it was left at.
     */
    private fun resumeLastSession(state: PlayerState) {
        val playerId = currentPlayer ?: return
        if (!isLocalPlayer(playerId) || resumeAttempted) {
            return
        }
        val prefs = appContext.prefs
        val lastPlayer = prefs.lastSessionPlayer
        val age = System.currentTimeMillis() - prefs.lastSessionTimestamp
        val resumable = prefs.resumePlayback && lastPlayer == playerId &&
            age < SESSION_MAX_AGE.inWholeMilliseconds
        Diag.log(
            "resume",
            "resumable=$resumable player=$playerId lastPlayer=$lastPlayer " +
                "wasPlaying=${prefs.lastSessionWasPlaying} ageMs=$age " +
                "position=${prefs.lastSessionPosition} state=${state.playbackState} " +
                "powered=${state.powered}"
        )
        if (state.playbackState == PlayerStatus.PlayState.Playing || !resumable) {
            resumeAttempted = true
            return
        }
        if (!state.powered || state.currentSong == null) {
            // Wait until the player is powered on and knows its playlist
            return
        }
        resumeAttempted = true
        val position = prefs.lastSessionPosition
        // The server's own position is not necessarily used for the stream it starts next, so
        // seek explicitly: immediately for a paused session, after playback was started
        // otherwise (where the seek needs a running stream to apply to).
        val restartPlayback = prefs.lastSessionWasPlaying
        Diag.log(
            "resume",
            "restoring position $position, restartPlayback=$restartPlayback"
        )
        launch {
            if (restartPlayback) {
                connectionHelper.changePlaybackState(playerId, PlayerStatus.PlayState.Playing)
            }
            if (position > SESSION_MIN_POSITION.inWholeSeconds) {
                if (restartPlayback) {
                    delay(SESSION_SEEK_DELAY)
                }
                connectionHelper.updatePlaybackPosition(playerId, position)
            }
        }
    }

    private fun Playlist.PlaylistItem.toMediaItemDataBuilder(position: Int): MediaItemData.Builder {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(extractIconUrl(appContext)?.toUri())
            .build()
        return MediaItemData.Builder(position)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId("$position-${hashCode()}")
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setMediaMetadata(metadata)
    }

    private class PendingPlayerState {
        var status: PlayerStatus? = null
        var playlist: Playlist? = null
    }

    private data class PlayerState(
        private val status: PlayerStatus,
        private val nowPlayingFallback: Playlist.PlaylistItem?,
        val playlist: Playlist?
    ) {
        val currentVolume get() = status.currentVolume
        val currentSong get() = status.playlist.nowPlaying ?: nowPlayingFallback
        val currentSongDuration get() = status.currentSongDuration
        val currentPlayPosition get() = status.currentPlayPosition
        val playlistPosition get() = status.playlist.currentPosition - 1
        val playbackState get() = status.playbackState
        val powered get() = status.powered
        val muted get() = status.muted

        fun isCompleteAndConsistent() =
            playlist != null && status.playlist.lastChange == playlist.timestamp
    }

    private data class UnacknowledgedPlayerStateChange(
        val absolutePlaylistPosition: Int? = null,
        val playlistPositionOffset: Int? = null,
        val positionInTrack: Duration? = null,
        val playState: PlayerStatus.PlayState? = null,
        val song: Playlist.PlaylistItem? = null
    )

    companion object {
        // Time a remembered session is considered worth resuming.
        private val SESSION_MAX_AGE = 12.hours

        // Don't resume positions very close to the start of a track.
        private val SESSION_MIN_POSITION = 5.seconds

        // Time to wait after starting playback before seeking to the resumed position; the seek
        // needs a running stream to apply to.
        private val SESSION_SEEK_DELAY = 2.seconds

        // Grace period after (re)connecting during which reported state is not remembered; the
        // server may still report the player as stopped before restoring its previous state.
        private val SESSION_SETTLE_TIME = 10.seconds

        // Minimum time between two stored session positions.
        private val SESSION_STORE_INTERVAL = 15.seconds
    }
}
