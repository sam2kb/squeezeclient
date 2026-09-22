# Now playing: the position slider moves back while the user drags it

**Problem**: while dragging the progress thumb on the now playing screen, the thumb is pushed back
towards the current playback position, and it jumps back once more right after the drag ends, until
the position that was seeked to is reported by the server. Seeking in a large step is practically
impossible - the thumb only creeps forward in small increments. In the worst case the app crashes
in `Slider` when the song changes while the slider is out of its range.

**When exactly**:
- While the screen is open and the player is *playing*: states arrive several times per second and
  the one-second interpolation job also writes the slider, so anything that moves the thumb is
  fighting the finger the whole time. While paused there is no such stream, which is why the
  effect is only really visible during playback.
- Right after the release: the seek is sent 200 ms after the last movement, and the new position is
  only reported after the round trip through the server (it restarts its stream at the requested
  position), so the slider shows the old position again for a moment.
- The crash needs one more ingredient: the song has to change while a position update is being
  held back, so that the slider keeps a value from the previous (longer) song while `valueTo`
  shrinks. `Material Slider` then throws `IllegalStateException: Slider value(438.49298) must be
  greater or equal to valueFrom(0.0), and lower or equal to valueTo(315.546)` while drawing.

**Why** (mechanism):
- The Material `Slider`'s value is both the user's input and the display. The screen writes
  `progressSlider.value` from the reported position (`update()` and the `timeUpdateJob` ticker),
  and those writes move the thumb exactly like a drag does.
- The change listener is what sends the seek (`fromUser` tells user input from programmatic
  writes), but nothing keeps the screen from writing the reported position into the slider at any
  time - including while the user is dragging it.
- Seeking itself is debounced by 200 ms (`sliderDragUpdateJob`) to avoid a request per pixel of
  movement, so nothing is confirmed while the finger keeps moving; the value displayed in the
  meantime is still the one from the last state.
- After the seek the server restarts its stream (`strm-t` + `strm-s`) at the requested position;
  until its status for that position arrives, the app keeps displaying the pre-seek position.
- The one-second interpolation job makes it worse: it re-derives the position from the last state
  every second and writes it to the slider as well, so the thumb is pushed back even when no new
  state arrived.

**Reproduction**:
1. Start playback from the library.
2. On the now playing screen, drag the progress thumb slowly and keep holding it: the thumb moves
   back towards the current position while the finger is still down.
3. Release: the thumb jumps back to the old position for a moment before it moves to the seeked
   position.
4. For the crash: hold the thumb (or keep a pending seek) while the next song starts and is
   shorter than the previous one.

**Suggested minimal change** (implement however you prefer):
- mark an active drag with a slider touch listener (`onStartTrackingTouch`/`onStopTrackingTouch`)
  and don't apply reported positions while it is in progress,
- send the seek when the touch ends; the delayed path stays for keyboard and accessibility
  changes,
- keep the slider at the requested position until a state reports a position close to it (with a
  timeout as a fallback, in case the request never lands),
- whenever `valueTo` is updated, make sure the current value stays inside the new range (the
  update above may be held back).

Diff: one file, +70 / -5 (`ui/nowplaying/NowPlayingFragment.kt`); only *when* positions are
applied to the slider changes, nothing about how they are fetched or sent.
Branch: `fix/slider-drag` (`3d4c247`, off `upstream/main` `6dacef7`), pushed to the fork (`origin`),
not to upstream.

## Follow-up case: a seek that outlives its song (2026-09-22)

The pending position above is only dropped when a position close to it arrives or the settle
timeout passes. That state was not tied to the song it was requested in, so seeking and then
switching to another track left the slider waiting for a position that never arrives in the new
song: the thumb stayed at the seeked position (clamped to the new song's duration, i.e. at the end
of the bar) and the real positions were ignored until the timeout - 5 s after the last seek, so
seeking several times before pressing Next extended it further. Reproduction: seek 3 times in a
track, press Next, watch the bar sit at the end of the new track and then jump back.

Fix: remember the song a seek was made in and forget the seek when another song plays.

## For the reviewer

The branch is a single commit (`3d4c247`, the follow-up case included) on top of upstream `6dacef7`;
it builds and lints on its own (see `docs/drafts/check-report.txt`). It conflicts with
`fix/local-position-display` in `ui/nowplaying/NowPlayingFragment.kt` (one small hunk - both drafts
edit where positions are applied to the slider, that one is the older of the two).
