# The play/pause button only reacts after the server round trip

**Problem**: pressing play or pause in the now playing screen looks like it did nothing for a
moment - the icon changes only when the server reports the new state (measured 0.6 s against a
server on the local network, longer over a phone connection). The natural reaction is to press
again, which sends a second command: the log of a session with a flaky connection shows pause
arriving three to six times per press.

**When exactly**: every press on the button in the now playing screen, and its long-press for stop.
While the connection is down the press is sent as well, although it cannot be delivered - and the
icon then shows a state that never takes effect.

**Why** (mechanism):
- the button renders `status.playbackState`, i.e. what the *server* last reported: the press itself
  changes nothing locally, it only sends `ChangePlaybackStateRequest` and waits.
- the same is true for a press that the app handles elsewhere (head unit, media key): the screen
  follows the server, not the command that was just sent.

**Evidence** (device log, a press through the media session with the same round trip):
```
09:59:01.011  cmd: setPlayWhenReady(false)          <- press
09:59:01.616  status: recv state=Paused             <- server answer, 605 ms later
09:59:01.617  state: applied ... state=Paused
```
Before the change the displayed state changed with that answer; a status for the previous revision
that was still in flight flipped it back first.

**Suggested minimal change** (implement however you prefer):
- remember the play state a press asks for and render that until the server reports the same state,
- drop the remembered state after a short timeout, so a command that gets lost falls back to the
  server's state instead of leaving a state behind that never happened,
- while the connection is down, do not send the press at all and tell the user instead.

Diff: one file, +59 / -7 (`ui/nowplaying/NowPlayingFragment.kt`).
Branch: `fix/nowplaying-play-pause-response` (`c1e2bfc`, off `upstream/main` `6dacef7`), pushed to
the fork (`origin`), not to upstream.

## For the reviewer

One commit (`c1e2bfc`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). It conflicts with `fix/local-position-display` and `fix/slider-drag`
in `ui/nowplaying/NowPlayingFragment.kt` - all three add lines in the same two places (the field
block and the state binding).

The media session has the same latency and is fixed in `fix/mediasession-pending-track`; the two are
independent, but a reviewer may prefer to see them as one change ("react to a press immediately,
wherever the user made it").
