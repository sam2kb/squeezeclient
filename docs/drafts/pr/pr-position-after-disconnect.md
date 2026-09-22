# Built-in player: position jumps and restarts at 0:00 after a connection loss / track change

**Problem**: with the built-in player, the progress display jumps around: after a network
interruption the audio continues but the display restarts at 0:00, after a track change it starts
at the *previous* track's position, and after a pause it follows the jump the server made while
the player was not connected. Sometimes the track then advances to the next one shortly after the
connection returns.

**When exactly**:
- when the connection between the built-in player and the server is down long enough for the
  server to give up on the player (our socket read fails and the reconnect loop runs; from the
  server's point of view the player stopped sending status messages): it stops the stream
  (`strm-t`) and starts a new one (`strm-s`) when the player is back - usually as a burst of
  several `strm-s`;
- after every track change the *app* initiated (Next/Previous, or a controller's index request):
  the server starts the new stream ~150-300 ms later, while its status still describes the
  previous track;
- after a pause, when the server restarts the stream on resume at the position it deduced while
  the player was gone;
- when the server resumes a player it restored on its own (phone/app restarted while the server
  kept its "playing" state).

**Why** (mechanism):
- slimproto: the server starts a stream by telling the player where it begins, and the player
  reports its position with periodic status (`STAT`) messages. LMS combines those reports with
  the assumption that everything it sent to the player was consumed.
- While the socket is dead no reports arrive, but the server's clock does not stand still: it
  advances while it believes the player is playing, pauses when it believes the player is gone,
  and pins itself at the song duration once it considers the track finished. After a few
  interruptions its position was about two minutes ahead of what was audible.
- On the app side, the position was derived from the *current* stream only
  (`player.determinePlaybackPosition()`, i.e. time since the current media item started), which is
  zero after every `strm-s`. A restarted stream therefore restarted at 0:00 even though playback
  continued.
- The position the server reported right after a restart was adopted unconditionally - but at
  that moment it still belongs to the stream we came from: measured in the logs, a track change
  started at the previous track's position (21 s, 43 s, 2m5s, 2m15s ...). In one log, 23 of 46
  of these reads adopted a value above 3 s for a brand-new stream. That value is then also
  reported back to the server, so the wrong position spreads.
- The server's position then stays wrong (or the app's does), which is how LMS ends up past the
  end of the track and advances to the next one - the "starts the next track after a blip"
  symptom.
- Asking the server to move to a position (`["time", N]` via the position-update request) makes
  it seek: it restarts its stream at N (`strm-t` + `strm-s`). Handing our position over therefore
  resynchronises the server; the CometD channel this goes through is not usable in the first
  seconds after a blip, hence the retries.

**Guards** (to keep this from fighting the user):
- position differences below 5 s are left alone (a seek would needlessly restart the stream),
  differences above 10 minutes are treated as "not the same track";
- seeks and track changes that we sent ourselves are remembered for 3 s - the stream the server
  starts right after them plays what the server chose;
- restarts within 5 s of another restart count as one burst, so the position stays continuous
  across all of them;
- the server's position is only read 2 s after the restart, and only adopted when the server
  actually resumed *inside* the song (its position is ahead of the time the new stream has been
  running); a song that starts at its beginning stays at zero.

**Evidence** (measured with a diagnostic build; position 2:37 when the connection dropped):
```
position kept counting across the restart  2:37 -> 2:55 -> 3:04 -> 3:12
hand-off: server 3:50, ours 3:24 -> asked the server to continue at 3:24
track change: strm-s at 18:18:05, server position read as 21 s (= previous track), base jumped to 21 s
```
Before the change the same situations restarted the display at 0:00 or at the previous track's
position.

**Reproduction**:
1. Play a track from the library with the built-in player.
2. `adb shell cmd wifi set-wifi-enabled disabled`, wait ~10 s, then
   `adb shell cmd wifi set-wifi-enabled enabled`.
3. Compare the progress display with the audio: the audio continues, the display restarts at 0:00.
   The server's own position (`["time","?"]`) shows the offset it resumed at.
4. Press Next on the phone: the display starts at the previous track's position for a moment
   (before the fix).

**Suggested minimal change** (implement however you prefer):
- remember where the current stream started and continue counting from there when the server
  restarts the same stream; treat a burst of restarts as one restart;
- after such a restart, ask the server to continue at the position we are actually at (retried,
  because CometD needs a few seconds to come back); leave the position alone when it is within a
  few seconds or implausibly far away; do this for restarts after a pause as well, where the
  server's clock has moved on;
- for a stream the server starts by itself, read its position only after a short delay and adopt
  it only when it resumed inside the song.

**Second finding**(measured after the first fix was in): right at a stream flush the *player's own*
position jumped by up to 30 s within milliseconds - `strm-t position=3m 35.663s` followed 8 ms later
by `strm-s position=4m 5.267s`. `determinePlaybackPosition()` is derived from the audio track's
timestamp (`framePosition + skipped frames`), which still refers to what was just flushed. The jump
was used as the position to continue from (so the slider jumped forward by 30 s) and was reported
to the server as well, which then believed the song was nearly over and moved to the next one.

Guard: the position may not advance faster than real time - it is clamped to the time that actually
passed since it was last computed, and the tracking is reset whenever the base legitimately changes
(a new stream, an adopted server position).

**Third finding** (live test, real 70 s outage): the stream the server starts in *response to
our hand-off request* was appended to the player (`addMediaSource`) like a stream for the next
playlist item, so the player kept playing the previous stream while the app's bookkeeping already
belonged to the new one. Playback then sat still - the reported position stayed at 246.96s for ten
seconds with a bogus buffer reading. Fixed by passing whether the stream continues ours into
`play()`: a restarted stream replaces the current one, a stream for the next item is still
appended (that is how gapless transitions are pre-buffered). The hand-off also marks its own
request so the restart it causes counts as a continuation instead of relying on the request-window
heuristics.

After the fix, the same scenario: hand-off fired (`server=97 ours=1m28.627s difference=9`), the
player resumed, the position kept following the audio (no backwards jumps) and the device volume
was untouched.

Diff: 5 files, +275 / -5 (`service/localplayer/LocalPlaybackService.kt`,
`service/localplayer/LocalPlayer.kt`, `cometd/ConnectionHelper.kt`,
`service/localplayer/PositionChangeRequests.kt` (new)).
Branch: `fix/position-after-disconnect` (`759b462`, off `upstream/main` `6dacef7`), pushed to the
fork (`origin`), not to upstream.

## For the reviewer

One commit (`759b462`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). It carries both this change and the position-after-seek fix
(`build/pr-position-after-seek.md`), because that one was written on top of `PositionChangeRequests`
and the same service code - the two belong in one PR anyway, they have no meaning apart.

It conflicts with `fix/local-position-display` in `service/localplayer/LocalPlaybackService.kt`
(one small hunk: that draft adds the position calculation this draft replaces parts of).
