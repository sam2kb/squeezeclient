# Media session reports the previous song for a moment after our own track change

**Problem**: after a track change the app initiated itself (media Next/Previous), the media session
briefly reports the *previous* song's metadata while the playlist index already points at the new
track. A head unit that switched optimistically on the button press flips back to the previous song
and then forward again - visible as new -> old -> new.

**When exactly**: on every Next/Previous press that the *app* handles, i.e. when the media session
is asked to `COMMAND_SEEK_TO_NEXT`/`COMMAND_SEEK_TO_PREVIOUS` (head unit button, media key, now
playing screen) while we are connected to the server. It does *not* happen when the server advances
the track on its own (end of playlist, another controller pressed next): that change arrives as a
status, without any pending state. It also does not happen while disconnected, since nothing is
reported optimistically then. Measured duration in the log below: about 1.3 s.

**Why** (mechanism):
- media3 publishes a new state to every connected controller whenever `invalidateState()` runs; a
  head unit renders what it receives immediately, so the first report after the button press decides
  what is displayed.
- To bridge the time until the server confirms the press, the player keeps an
  `UnacknowledgedPlayerStateChange` describing the expected result (playlist offset, position in
  track, play state) and reports that instead of the server's still-old state. It does not carry the
  song, though (`getState()` took `currentSong` from `playerState`, i.e. the old revision, while
  `currentIndex` already included the pending offset).
- So the metadata published right after the press describes the previous track with the new index -
  which is exactly the combination a head unit cannot reconcile.
- The pending state is dropped in `applyPlayerState()` as soon as any status is applied (it is also
  bounded by a 3 s revert job). The statuses arriving in that window still describe the *previous*
  playlist revision, because the server has not processed the request yet; the first of them clears
  the pending state and the metadata falls back to the old song until the status for the new
  revision arrives. Fixing this means keying the pending state on the song, not on the index.

**Evidence** (real run, titles replaced by placeholders):
```
18:44:36.471  cmd: seek cmd=9 index=11 posMs=C.TIME_UNSET      <- media Next
18:44:36.472  unack: reporting UnacknowledgedPlayerStateChange(..., playlistPositionOffset=1, ...)
18:44:36.475  getState: published='Track 1' index=11 items=21  <- previous song, new index
18:44:37.799  state: applied song='Track 2' idx=10 state=Playing
18:44:37.803  getState: published='Track 2' index=10 items=20
```
The index is correct immediately; only the song lags, for about 1.3 s in this run.

**Reproduction**:
1. Play a playlist with the app's built-in player and connect an AVRCP head unit.
2. Press Next on the head unit.
3. Watch the head unit display (or the phone): the previous title/artwork is reported for a moment
   after the index moved. Without a head unit: `adb shell input keyevent 87` (NEXT) while the app's
   session is active, then poll `adb shell dumpsys media_session | grep TITLE`.

**Suggested minimal change** (implement however you prefer):
- carry the expected song in the pending state (it can be taken from the already fetched playlist at
  `playlistPosition + offset`),
- report it from `getState()` while the change is pending,
- in `applyPlayerState()`, only drop the pending state once the applied state shows that song; the
  existing 3 s revert job still bounds it.

Diff: one file, +35 / -8, nothing outside the reported metadata is touched.
Branch: `fix/mediasession-pending-track` (`4f3caf6`, off `upstream/main` `6dacef7`), pushed to the
fork (`origin`), not to upstream.

## For the reviewer

One commit (`4f3caf6`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). It conflicts with `fix/local-position-display` in
`service/mediasession/SqueezeboxMediaPlayer.kt` (one small hunk, both add lines in the same place).
