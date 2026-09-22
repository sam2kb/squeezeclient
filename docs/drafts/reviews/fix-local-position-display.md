# Review — `fix/local-position-display` (`9fbb028`)

**Reviewed against** upstream `6dacef7`, branch tip `9fbb028`, diff 4 files +108/−10. Read-only review; no build was run here (see `reviews/README.md`).

Line numbers are from the branch (`git show origin/fix/local-position-display:<path>`); device-dependent statements are marked as hypothesis.

## 1. Verdict

The diagnosis is right, and the drift is structural rather than a hunch: the "server position" shown is
`Clock.System.now()` minus the server's sample timestamp (`PlayerStatus.kt:89-99`), so it keeps running while
the sample is stale, and the built-in player is the only player that knows better. The fix is in the right
place, and a holder is genuinely needed (`LocalPlaybackService.onBind` returns `null`,
`LocalPlaybackService.kt:117-120`). But it is not proportionate: the 55-line holder carries one value that is
per-player and two that are not, and it also changes what is reported to the server.

## 2. Correctness

The author's suspicions, checked:

- **"Only valid while the local player is active" — does not hold for the display path.** The key written at
  `LocalPlaybackService.kt:357` is `slimproto.playerId` (`SlimprotoSocket.kt:56`), the id handed to the media
  session (`LocalPlaybackService.kt:141-143`) and matched against the server's list (`MediaService.kt:219`);
  `PlayerId` is a data class (`Player.kt:45`). `forPlayer` matches only the built-in player
  (`LocalPlayerPosition.kt:29-32`), and both display sites key on the player they show
  (`NowPlayingFragment.kt:91,409`; `SqueezeboxMediaPlayer.kt:247`).
- **"Can it grow per player id" — no.** `current` is one `Pair`, overwritten per update
  (`LocalPlayerPosition.kt:13,48-50`); `clear()` misses `songDuration` and `songGeneration` (`:52-54`).
- **"Conflicts with three drafts" — holds.** The hunks overlap all three (`NowPlayingFragment.kt`
  404-413/450-475, `LocalPlaybackService.kt` 329-367, `SqueezeboxMediaPlayer.kt` 404-414), as
  `check-report.txt:41-43` records; with `position-after-disconnect` the overlap is also semantic — it
  already maintains `streamStartPosition` for the same quantity (its `LocalPlaybackService` hunk at +93).

What does not hold:

1. **The duration and the counter are global; only the position is keyed**
   (`LocalPlayerPosition.kt:13,17,21`). Both are fed from `applyPlayerState`
   (`SqueezeboxMediaPlayer.kt:407-411`), which follows `currentPlayer`: `prefs.lastSelectedPlayer`
   (`MediaService.kt:105`) or whichever player the app shows when it is backgrounded
   (`MainActivity.kt:230-234`). The built-in player keeps playing meanwhile —
   `MainActivity.changePlayer` (`:371-405`) does not stop it — so a track change on the *remote* player
   resets the built-in player's offset on its next status (`LocalPlaybackService.kt:337-346`) and drops its
   position to ~0 mid-song — including the value now sent to the server (`:363`) — while `clamp` (`:354`)
   bounds it by the remote song's duration.
2. **A fresh service instance disagrees with the process-wide counter.** `lastSongGeneration` is an instance
   field initialised to 0 (`LocalPlaybackService.kt:91`) while `songGeneration` lives for the process and is
   never reset (`LocalPlayerPosition.kt:21`). A new instance in a live process — the service is stopped and
   restarted by `triggerStartOrStop` (`:480-486`) while `MediaService` keeps the process alive — reads
   `generation != 0` on its first status, treats it as a song change and resets the position to ~0 mid-track.
3. **The offset can outlive the counter it was sampled from, and `clamp` is not always there.** The re-sample
   happens only on a generation change (`:337-346`), but the value subtracted from is one audio sink's frame
   count plus `skippedFrames` (`LocalPlayer.kt:237-247`; a flush resets the latter,
   `LocalPlayerAudioProcessor.kt:50-55`) — not a clock for the player. Anything that restarts that sink
   without a *different* song being reported (a mid-song stream restart, the case
   `position-after-disconnect` exists for; a seek at `NowPlayingFragment.kt:244`; a track change whose status
   arrives before the sink is rebuilt) makes the result negative, and `clamp` pins the display to 0 for as
   long as the accumulated offset. With `songDuration == null` the clamp is a no-op by design
   (`LocalPlayerPosition.kt:35-36`) — radio streams have no duration (`NowPlayingFragment.kt:442-446`) — so
   the negative reaches the STAT packet as a signed int (`SlimprotoSocket.kt:389,391`), which the base never
   sent (`6dacef7:LocalPlaybackService.kt:336`). *Hypothesis, one device run settles it*: gapless album, then
   seek or Wi-Fi blip. The ordering is possible from the code alone — `StreamStart` sends its status before
   the player gets the new source (`:394-407`).

## 3. Over/under-reach

- **Over:** the PR text calls this display-only ("`setContentPositionMs` and the now playing screen"), but
  `slimproto.sendStatus` now gets the offset, clamped value (`LocalPlaybackService.kt:359-367`, value at
  `:363`) instead of the raw stream position — protocol-visible, and the client never reports being past the
  end of a song any more, which the PR text's own mechanism section calls the signal the server uses to
  schedule the next track.
- The brief calls the holder "a process-wide StateFlow per player"; it is a pull-only `@Volatile` holder
  (`LocalPlayerPosition.kt:12-21`) read by the fragment's ticker (`NowPlayingFragment.kt:469-481`) and by
  `getState()` on `invalidateState` (`SqueezeboxMediaPlayer.kt:415`). Pull is defensible; the text should say
  pull.

## 4. Surgical alternative

Same user-visible result, no protocol change, no cross-player state, no counter. Sketch, not compiled here.

```kotlin
// LocalPlayerPosition.kt — the whole holder: one value, one key
object LocalPlayerPosition {
    @Volatile private var current: Pair<PlayerId, Duration>? = null
    fun forPlayer(playerId: PlayerId?) = current?.takeIf { it.first == playerId }?.second
    fun update(playerId: PlayerId, position: Duration) { current = playerId to position }
    fun clear() { current = null }
}
```

```kotlin
// LocalPlaybackService.kt — count from the last track start this player reported
private var songStartOffset = Duration.ZERO

private fun noteTrackStart() {
    // The stream may continue into the next song or start a new one; either way, what the audio
    // sink renders after this point belongs to the new song.
    songStartOffset = player.determinePlaybackPosition(System.nanoTime())
}
// call where sentTrackStartStatus flips to true: handlePlaybackStart() (:234-239) and
// onPlaybackAdvancedToNextTrack() (:194-197), before sendStatus(TrackStarted)

val streamPosition = player.determinePlaybackPosition(nowNanos)
slimproto.sendStatus(type, elapsed, player.readyForPlayback, streamPosition, ...)  // unchanged
LocalPlayerPosition.update(slimproto.playerId, streamPosition - songStartOffset)
```

Clamp at the two consumers, where the shown player's duration is already at hand
(`NowPlayingFragment.kt:407` → `status.currentSongDuration`; `SqueezeboxMediaPlayer.kt:245-251` →
`playerState.currentSongDuration`, `:447`). That deletes `updateDuration`, `clamp` and `noteSongChanged`, and
with them the cross-player leak: ~3 files, roughly +35/−8.

The reset also becomes reliable: `onPlaybackAdvancedToNextTrack` is the player's own item transition
(`LocalPlayer.kt:268-280`), the moment the audio crosses the boundary, so sampling there is self-consistent
whether or not the sink is rebuilt then. The current code samples when the *media session* was told
(`LocalPlaybackService.kt:345`) — one status interval late, and that lateness stays as a constant lag for the
rest of the song. Both variants should also reset on the next `handlePlaybackStart`, because the listener
skips the callback when the transition happens while buffering (`LocalPlayer.kt:277`).

## 5. Contract/ripple

- `PlayerStatus.currentPlayPosition` has exactly three consumers in `app/src/main` (`git grep -n
  currentPlayPosition origin/fix/local-position-display -- app/src/main`): its definition
  (`PlayerStatus.kt:95`) and the two display sites, `SqueezeboxMediaPlayer.kt:249` and
  `NowPlayingFragment.kt:409` — both updated, which is right.
- `slimproto.sendStatus`'s signature is unchanged (`SlimprotoSocket.kt:368-397`); only the `time` field's
  value changes. The media session's precedence chain survives: a pending seek still wins
  (`SqueezeboxMediaPlayer.kt:245-250`), so this does not fight `slider-drag`.
- `LocalPlayerPosition` is referenced from exactly six places (same grep): `clear` `:161`, `songGeneration`
  `:338`, `clamp`/`update` `:354,357`, `forPlayer` (`SqueezeboxMediaPlayer.kt:247`,
  `NowPlayingFragment.kt:409`) and the writers (`SqueezeboxMediaPlayer.kt:407,410`). No other module.

## 6. Concurrency/lifecycle

- All writers are on the main thread (`applyPlayerState` from the cometd collector,
  `SqueezeboxMediaPlayer.kt:338-343`; `sendStatus` from `lifecycleScope.launch`, e.g.
  `LocalPlaybackService.kt:181,195,213`), so the `@Volatile`s are belt-and-braces and the non-atomic
  `songGeneration++` (`LocalPlayerPosition.kt:45`) has a single writer. No new job, no new cancellation path,
  nothing holds a `Context`. `current` and `songDuration` are read separately (`:30-31`), so a reader can pair
  a fresh position with a stale duration — benign for one status interval.
- Lifetime: the holder knows nothing about the playback state, and `clear()` runs only in `onDestroy`
  (`LocalPlaybackService.kt:161`), so `forPlayer` keeps preferring the frozen local value for a stopped
  player. *Hypothesis*: a stale bar position; gate it on the playback state.

## 7. Style

- Added lines: none over 100 columns, longest 98 (`git diff 6dacef7 origin/fix/local-position-display |
  grep '^+' | awk 'length($0)>100'` → empty); all ten removed lines are the replaced logic, no reformatting
  of untouched lines.
- Nits: `LocalPlayerPosition.kt:15` and `:38` are the same sentence twice; the `songGeneration` KDoc ("the
  song the local player plays", `:19`) overstates what the code counts (finding 1).

## 8. Tests

Not warranted in this shape, and the valuable part is not testable with what exists: the tree has only
`ListResponseTest.kt` and `FlacMetadataCachingDataReaderTest.kt`, and JUnit 4 is the only test dependency
(`app/build.gradle.kts:137`) — no Robolectric, no mockk, no coroutines-test. A plain JUnit test of
`LocalPlayerPosition` alone is feasible and would pin the keying, but not the offset/generation logic, which
sits inside a `Service` with an `ExoPlayer` and a socket; testing that means inventing a seam. With the
smaller design the arithmetic becomes a plain class and four JUnit cases would cover both ordering bugs.

## For the maintainer — the short form

Real problem, right place, wrong-sized state. What to ask for:

1. Keep the player-keyed position; drop `songDuration`, `songGeneration`, `clamp` and `updateDuration` from
   the holder and clamp at the two display sites, where the shown player's duration is already there.
2. Reset the offset where the built-in player reports a track start (`LocalPlaybackService.kt:194-197`,
   `:234-239`), not from the media session — that removes the cross-player reset, the fresh-instance mismatch
   and the one-status lag.
3. Leave `slimproto.sendStatus` reporting `player.determinePlaybackPosition(nowNanos)`; if the STAT change is
   wanted, split it out and say so in the PR text.
4. One device run settles the last hypothesis: gapless album, then seek or Wi-Fi blip, with the app's selected
   player set to a remote player — watch for a position pinned at 0 or a negative value.
5. Land order: this is the oldest and widest-reaching of the four position drafts, so land it last, together
   with `position-after-disconnect`, which already owns the "position our stream started at" idea.
