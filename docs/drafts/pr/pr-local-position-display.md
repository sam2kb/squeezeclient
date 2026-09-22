# Progress display for the built-in player uses the server's estimate and drifts

**Problem**: with the built-in player, the progress shown by the media session (head unit,
notification, lockscreen) and on the now playing screen drifts away from what is playing: after a
few interruptions it lags behind by tens of seconds, it jumps forwards and backwards, and at the end
of a track it stays at 100% while the next track already plays.

**When exactly**:
- always while the built-in player plays - it is the only player for which the position shown is
  not the one being played (for remote players the server is authoritative and correct);
- it becomes obvious after connection losses: the server's clock keeps running while the player
  plays from its buffer, and stops at whatever value it reached when it decides the player is gone;
  from then on the display carries that constant offset, growing with every interruption;
- at every track end: the server keeps reporting the song duration until the next status, so the
  display sits at 100% (and the slider at its end position) although the next song is already
  playing;
- after a pause, the server's clock pauses with it while the player may have kept playing, so a
  pause/resume pair adds another offset (136 s measured in one session).

**Why** (mechanism):
- The position published by a media session is a *sample*: controllers (head units, lockscreens,
  `MediaSessionManager`) interpolate it forward using the playback speed and the elapsed realtime
  until the next update. A wrong sample therefore stays visibly wrong until the next update, and it
  is what the head unit shows.
- For the built-in player, that sample came from the server's status
  (`PlayerStatusResponse.playPosition`), i.e. from a clock that estimates our position as described
  in the other change (it advances while disconnected, pauses when it believes we stopped, and pins
  at the duration when a track finished).
- The player itself knows its position exactly (`player.determinePlaybackPosition()`); the only
  thing it needs from the server is the song duration, which is what the clamp uses.
- Clamping matters: a stream can be longer than the song (the server appends the next track to it),
  so a position beyond the song's end is possible; that value makes a `Slider` unhappy
  (`value > valueTo`) and confuses the server as well.

**Evidence** (measured):
- the difference between the server's position and what was playing reached 136 s after a
  pause/interruption sequence;
- `time == duration == 181.96` was reported while the next song was already audible, and the
  progress display stayed at 100% until the next status arrived.

**Reproduction**:
1. Play tracks with the built-in player and interrupt the Wi-Fi a few times (or pause/resume from
   the server side).
2. Compare the progress in the media session / now playing screen with the audio: the difference
   grows.
3. `["time","?"]` for the player shows the drifted value that is being displayed; at the end of a
   track the bar stays at 100%.

**Suggested minimal change** (implement however you prefer):
- publish the position the built-in player actually plays together with the song duration the
  server knows, as long as it is the player we play,
- use it for the media session's `setContentPositionMs` and on the now playing screen; clamp it to
  the song duration,
- everything else (remote players, the server's position, the seek handling) stays as it is.

The position holder is a process-wide value keyed by player ID, because both the media session and
the now playing screen need it without binding to the playback service. This change is independent
of the position-continuity one, but the two compose: with both, the local player reports its own
position and keeps that position across server-initiated stream restarts.

**When the song changes inside a stream**: the server appends the next song to the running stream
when it plays gaplessly, and it does so when it considers the current song finished. No stream
command marks that boundary, so the position kept counting into the new song (measured: the slider
counted 4m5s -> 4m19s while the new song was playing from its beginning). The media session reports
the song change, so the player can start counting from the new song's beginning (measured from the
stream position at that moment).

Diff: 4 files, +108 / -10 (`service/localplayer/LocalPlayerPosition.kt` (new),
`service/localplayer/LocalPlaybackService.kt`, `service/mediasession/SqueezeboxMediaPlayer.kt`,
`ui/nowplaying/NowPlayingFragment.kt`).
Branch: `fix/local-position-display` (`9fbb028`, off `upstream/main` `6dacef7`), pushed to the fork
(`origin`), not to upstream.

## For the reviewer

One commit (`9fbb028`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`).

This is the oldest of the four drafts that touch position handling, and the three that were written
later each conflict with it in one file when both are merged:

- `fix/slider-drag` - `ui/nowplaying/NowPlayingFragment.kt` (both drafts edit where positions are
  applied to the slider),
- `fix/position-after-disconnect` - `service/localplayer/LocalPlaybackService.kt`,
- `fix/mediasession-pending-track` - `service/mediasession/SqueezeboxMediaPlayer.kt`.

Each conflict is a single small hunk (the branches just add lines next to each other), so whichever
of the pair lands second resolves it by rebasing; if this draft lands first, the other three need a
one-hunk rebase.
