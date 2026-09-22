# Review brief for the draft PRs

One section per draft: what it does, what was actually verified (and how), and what a reviewer
should be suspicious about. Everything here is knowledge from the session that produced the
branches; the mechanical checks (standalone compile, lint, conflicts) come from `check.sh` /
`check-report.txt`.

All drafts also live together on `main` (with their fixes integrated,
not as separate commits), which is what the test phone has been running, so their behaviour *in
combination* is what was used most of the time. The branch is not a PR; it is the review and test
ground.

## `fix/cometd-request-while-disconnected` (`1c453ab`, 2 files, +15/-4)

`ConnectionHelper.publishOneShotRequest` now throws `CometdClient.CometdException` instead of a bare
`IllegalStateException` when the client id is not there yet, and the paging load path catches it.

- Verified: live - three Wi-Fi blips plus opening the app during the re-handshake no longer produce a
  FATAL (`IllegalStateException` escaped into `BasePagingListFragment.ItemSource.load` before).
- Suspicious: whether the exception type is the right contract for the other callers; the change is
  small, but the paging fragment swallows *all* exceptions now.

## `fix/local-position-display` (`9fbb028`, 4 files, +108/-10)

Adds `LocalPlayerPosition` (process-wide StateFlow per player) and has the now playing screen and the
media session show the local player's own position instead of the server's, resetting it when the
song changes.

- Verified: blip tests - display and local position stay within 1-2 s of each other across a
  connection loss, where the server's value jumped +20-35 s. Song-change reset verified via
  `songGeneration`.
- Suspicious: it conflicts with three other drafts (see below); its value depends on the local player
  being the active one (the server's position is right for every other player). It is the oldest of
  the four position drafts, so the other three are the ones that would need a one-hunk rebase.

## `fix/mediasession-next-at-playlist-end` (`b10065d`, 1 file, +13/-6)

Clamps the reported playlist index and only advertises next/previous when such an item exists, which
fixes `IllegalArgumentException: currentMediaItemIndex must be less than playlist.size()` inside
`MediaSessionStub.seekToNext`.

- Verified: live - 5x Next at the end of a 4-track queue no longer crashes.
- Suspicious: nothing much, but check that hiding the commands does not confuse controllers that
  expect them.

## `fix/mediasession-pending-track` (`4f3caf6`, 1 file, +35/-8)

Carries the expected song inside the pending state, so `getState()` does not report the previous song
for ~1.3 s after a track change (the "new -> old -> new" flap on head units).

- Verified: headless - after a media Next the only published song is the new one; the old one is
  never republished.
- Suspicious: it is a workaround inside the maintainer's own rework of an earlier PR; the pending
  state should ideally be cleared reliably (it waits for the server to confirm the song). Conflicts
  with `fix/local-position-display` in `SqueezeboxMediaPlayer.kt` (one hunk).

## `fix/position-after-disconnect` (`759b462`, 4 files, +274/-5)

Keeps the position across server-initiated stream restarts (adopts the server's position only when it
resumed inside the song, asks the server to continue at our position when our stream was
interrupted), plus the position-after-seek change from this session, which has no branch of its own:
use the position a request asked for as the base of the stream answering it, and compare a stream
generation counter instead of the stream URL for the delayed adoption/hand-off (the URL is identical
for every LMS stream, so those guards never fired), and hand off the position after every
interrupted/pause-resumed stream.

- Verified: real 70 s outages (hand-off fired, playback resumed, position continuous, volume kept),
  and the seek/seek/pause/play sequence on the device (bar follows the requested positions, playback
  survives a pause right after a seek, track ends and transitions are correct).
- Suspicious: the hand-off costs a stream restart when it fires; the stream counter depends on every
  `strm-s` incrementing it; the draft is a hand-written port of code that lives on the integration
  branch (review the diff itself, it was not machine-generated). Conflicts with
  `fix/local-position-display` in `LocalPlaybackService.kt` (one hunk).
- Deliberately not done: correcting the server's own idea of the position right after a seek (it
  would need an extra position request, i.e. another stream restart, per seek; the hand-off corrects
  it whenever a stream restarts anyway).

## `fix/session-reannounce` (`a6b1af8`, 1 file, +28)

Re-announces the media session (`removeSession` + `addSession`, throttled, only while playing) when a
device sends a media button without being attached, which is what a manual Bluetooth toggle does.

- Verified: car logs (`device state is stale, re-announcing session`); *not* verifiable headless.
- Suspicious: `removeSession`/`addSession` timing with an active controller; the throttle value; it
  was written against the older media session implementation, so re-check whether the current code
  still needs it at all.

## `fix/slider-drag` (`3d4c247`, 1 file, +95/-5)

Two fixes in the now playing slider: a drag is not fought by status updates (touch listener, pending
seek, settle tolerance/timeout), and a pending seek is forgotten when the song changes (otherwise the
thumb stayed at the old position, clamped to the new duration, i.e. at the end of the bar, and real
positions were ignored for up to 5 s).

- Verified: device - the "seek, seek, seek, next" and "seek, seek, pause, play" sequences, with the
  bar position measured from the screen (fraction of the track) at every step.
- Suspicious: the hold logic is the most intricate part of the fragment; the fix assumes the song
  identity (title/artist/album) is stable for a given track. Conflicts with
  `fix/local-position-display` in `NowPlayingFragment.kt` (one hunk).

## `fix/volume-device-volume-fades` (`ba7f766`, 1 file, +61)

Ignores the volume the server sends while it fades into a new playback state (and while not playing)
and adopts the device volume around state toggles and external volume changes.

- Verified: live - device volume 9 stayed 9 across pause/resume, a Wi-Fi-only blip and a real 70 s
  outage with a server-side stream restart.
- Suspicious: it depends on the maintainer's rewritten volume handling (`updatePlayerVolume`,
  `lastSetVolume`); the 2 s window around toggles is a heuristic. An earlier, much larger attempt is
  on `fix/volume-follow-device-changes` (not part of the review set).

## `feature/nowplaying-favorite-toggle` (`6dc0c91`, 14 files)

Adds a favorite button to the now playing screen: shows the state of the playing track, toggles it in
one tap, hides itself while the state is fetched. Rebased onto `6dacef7` and squashed to one commit
during this session (the old branch had three commits on an older base).

- Verified: live, against the favourite list of the test account; the state fetch uses the
  current-track-info request that the stream start draft also builds on.
- Suspicious: UI taste is the one thing the maintainer has pushed back on before (an earlier PR that
  changed this screen was closed, see `build/pr61-reply.md` in the scratch directory - content:
  he dislikes added chrome in the now playing screen), so present it as a proposal with the
  screenshots in `screenshots/` and expect a no. It is also the only draft that adds UI strings.

## Stream start on resume (no branch: `f6fc998` on the integration branch)

Keeps the start of a file so a stream that the server resumes *inside* a file can still be decoded
(FLAC/Ogg need their first blocks/pages; MP3/ADTS do not). The reader reports the start of every
stream that begins at the beginning of a file, the service stores it per track (a few hundred bytes,
most recent four), and a stream that resumes inside a file is given the stored start.

- Verified: device with a FLAC library (capture, store, and the `using the stored stream start for our
  stream` path after a force-stop mid-track); 8 unit tests cover the reader (4 FLAC - capture, padding
  drop, frame-sync resume, failure without a stored start; 4 Ogg - setup-page capture, resume at the
  next packet page, continued-page skip, unknown codec). Ogg was unit tested only: the test library
  has no Ogg files.
- Suspicious: the pairing of a stream with a stored start. For containers without a stated duration
  it is limited to streams the server started for us (we saw the preload); for FLAC the duration from
  `STREAMINFO` has to match within 2 s. A wrong pairing would hand a stream the wrong file's start.
  Also: `f6fc998` deletes the FLAC metadata reader that upstream ships, so it is a replacement, not
  an addition - but it is not a branch because it needs the favorite toggle's current-track-info
  request (a cherry-pick conflicts in four files; porting it means re-adapting the
  `LocalPlayer`/`LocalPlaybackService` hooks by hand).
- Options that were rejected are documented in `pr/pr-stream-start-on-resume.md` (server download of
  the file start, priming the stream at 0, synthesizing `STREAMINFO` from a frame header).

## Conflicts between drafts (three pairs, all with `fix/local-position-display`)

| pair | file | note |
| --- | --- | --- |
| `fix/local-position-display` + `fix/slider-drag` | `ui/nowplaying/NowPlayingFragment.kt` | both edit where positions are applied to the slider |
| `fix/local-position-display` + `fix/position-after-disconnect` | `service/localplayer/LocalPlaybackService.kt`, `service/mediasession/SqueezeboxMediaPlayer.kt` | the position calculation vs the code that replaces parts of it, plus the track-change `note()` calls |
| `fix/local-position-display` + `fix/mediasession-pending-track` | `service/mediasession/SqueezeboxMediaPlayer.kt` | both add lines in the same place |
| `fix/mediasession-pending-track` + `fix/position-after-disconnect` | `service/mediasession/SqueezeboxMediaPlayer.kt` | both edit the Next/Previous handlers |

Each is one or two small hunks; whichever lands second needs those resolved. Every other pair
merges cleanly (see `check-report.txt`).
