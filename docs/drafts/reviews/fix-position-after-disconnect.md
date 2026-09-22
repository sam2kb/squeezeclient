# Review — `fix/position-after-disconnect` (`759b462`)

**Reviewed against** upstream `6dacef7`, branch tip `759b462`, diff 4 files +274/−5. Read-only review; no build was run here (see `reviews/README.md`).

## 1. Verdict

The diagnosis is right and so is the place: upstream keeps no position bookkeeping at all (the `StreamStart` handler was 13 lines, `6dacef7 LocalPlaybackService.kt:367-380`, and reported `player.determinePlaybackPosition()`, `6dacef7:336`, which is stream-relative and therefore ≈0 after every `strm-s`), so "carry a position base across a restart" and "a restarting stream must *replace* the stream we play" are the load-bearing ideas, and both are correct. The 274 lines are not earned by them: 10 new fields and 8 tuning constants decide two questions, and most of the correctness now rests on how fast LMS and CometD happen to be. The idea is worth keeping, roughly half of the added code is a candidate for deletion, and two of the findings below (C1, C3) do not depend on timing at all.

## 2. Correctness

What holds up (verified by reading the branch):

- The counter cannot double-count: `handleCommand` has exactly one caller (`LocalPlaybackService.kt:286`), `CommandPacket.StreamStart` exactly one handler (`:497`), and `player.play(` exactly one caller in the tree (`:549`, `git grep -n '\.play('`).
- `replace` (`LocalPlayer.kt:191,221`) fixes a real defect: upstream appended whenever the player was not IDLE (`6dacef7 LocalPlayer.kt:218-224`), so a blip restart was queued *behind* the old stream — the author's third finding, and the code supports it.
- The position-continuity fix itself (`streamStartPosition`, `:102,536-540`) is the only way to keep the number continuous across a `strm-s`; nothing upstream does that.

Counter-scenarios, worst first. Everything below is analysis of this diff, not a measurement:

- **C1 — the flags are sticky, so the *next* stream of any kind is classified as a continuation (hypothesis, likely in normal use).** `streamInterrupted` is set on socket-read failure (`:289`) and on a playback error (`:246`), `streamResumesAfterPause` on pause and on unpause (`:565,576`); both are cleared only by whatever `strm-s` arrives next (`:527-528`). A normal pause/resume does *not* restart the stream (LMS sends `strm-u`, `:575-587`), so the flag survives until the next track change — and that track change is then treated as our stream continuing: base = the old track's position (`:537`) and a hand-off asking the server to play the new track at the old position (`:519,542`). `HAND_OFF_MAX_DIFFERENCE = 10.minutes` (`:645`) does not reject a 1–3 minute difference, and `PositionChangeRequests.isRecent()` cannot help: it only blocks the base (`:511`), not the hand-off, and no track-change request is ever noted (C3). "Pause at 1:00, then press Next" is enough to make the built-in player seek a fresh track to 1:00 — Next and a list item both go around `updatePlaybackPosition` (`SqueezeboxMediaPlayer.kt:129`, `PlaylistFragment.kt:87`).
- **C2 — the delayed adoption can fire for a stream that is not playing yet (hypothesis, timing-dependent).** LMS sends the next track's `strm-s` while the current track is still playing — the app's own comment says so (`LocalPlayer.kt:162-167`) and the append path depends on it (`:226`). `checkServerPositionAfterRestart` (`:395-408`) then reads the server's position 2 s later, and while the previous item is still current that position is the *previous* track's, which passes the "server is ahead of the stream's running time" test (`:403`) trivially. The base then jumps by roughly the old track's position, and because line 407 zeroes `lastPlayerPositionRealtime`, the next `playerPosition()` returns it unclamped (`:365-368`). This is the exact failure the PR text documents for the unguarded version ("23 of 46 reads adopted a value above 3 s"); the 2 s delay makes it rarer, not impossible. What is *not* verified here: how far ahead LMS pre-sends, i.e. whether the 2 s window is actually crossed in practice — worth one log check with timestamps before trusting the delay.
- **C3 — the URL test cannot do the job (verified contradiction).** `command.uri.toString() == currentStreamUri` (`:512`) is, per the author's own seek PR text, provably a no-op: "it compared the stream URL, which is the same for every track the server streams, so the guard never fired" (`pr/pr-position-after-seek.md`; the URL is built from the server's request path, `SlimprotoSocket.kt:246-249,258`). If that measurement holds the term is constant-true and the entire "did the track change?" question rests on the 3 s request window — which nothing sets for a track change: `PositionChangeRequests.note` has exactly one caller (`ConnectionHelper.kt:326`), always with a position; `note(null)` for "switch track" is documented (`PositionChangeRequests.kt:5-10,21`) and never used, while Next/Previous go through `sendButtonRequest` (`SqueezeboxMediaPlayer.kt:129,135`, `NowPlayingFragment.kt:256-257`). Either the URL claim or this check is wrong; both cannot stand.
- **C4 — a failing hand-off is not retried and can be fatal (verified).** `connectionHelper.updatePlaybackPosition(...)` is called unguarded (`:440`) inside `lifecycleScope.launch` (`:415`). While CometD is down — the state the retry loop exists for — `publishOneShotRequest` throws a bare `IllegalStateException` (`ConnectionHelper.kt:392-393`), which nothing on this path catches and which no handler in `app/src` intercepts (`git grep CoroutineExceptionHandler` → no matches). A `CometdException` is different: it is turned into cancellation (`ConnectionHelper.kt:417-422`), so that failure silently abandons the hand-off — and skips the `PositionChangeRequests.clear()` after it (`:445`). Either way the loop never retries the request, only the query.
- **C5 — the generation guard has a window (verified mechanism, narrow impact).** The guard is checked before the ~5 s fetch (`:419`, `CONNECTION_TIMEOUT`, `ConnectionHelper.kt:650`), never after it, so a second interruption during that fetch lets the older hand-off send a stale position. Same shape in the adopt path: the state update happens after the suspending `sendStatus(Connecting)` (`:530`) while `streamGeneration++` is at `:532`, and that send really suspends (`SlimprotoSocket.kt:416,432`).
- **C6 — a slow hand-off round trip shows 0:00 (hypothesis).** If the answered restart takes longer than `SEEK_RESTART_WINDOW` (5 s, `:654`), `handOffDue` is false, the stream is taken for a server-initiated one and the base resets to zero (`:539`), until the adoption puts it back ~2 s later. Related: `fetchPlaybackPositionSeconds` returns null for "not connected" *and* for "the server says not playing" (`ConnectionHelper.kt:334-339`), so a paused server is polled ten times over 20 s (`:648-651`) with no result and no log line.

## 3. Over/under-reach

- The two questions ("does this stream continue ours?" and "is this the stream our request asked for?") are answered by one expression built from four flags and two windows (`:507-519`); `continuationUntil` is written in two places and read in two, `handOffPending` in two and one. The URL term should go (C3); the pause and the socket-loss cases should share one flag instead of two, and that flag should be dropped by our own change requests (C1).
- `PositionChangeRequests` is dressed as a general "what did we ask for" record but records only positions; either make `note(null)` real on the track-change paths or cut the comment to "the position the last position request asked for".
- The `note()`/`clear()` dance (`:440-445`) exists only because the hand-off is routed through the UI-facing `updatePlaybackPosition`; a bookkeeping-free request helper would remove both the set-then-clear race and the skipped `clear()` on cancellation.
- Framing: the PR text's "adopted unconditionally / 23 of 46" describes the unguarded intermediate version of this same code on the integration branch — upstream `6dacef7` has no adoption at all. The maintainer should not go looking for that upstream, and the text should say which evidence is about upstream and which about the drafts.
- Not one `Log.` line is added anywhere in the new logic (0 added `Log.` lines in the diff), while the PR text quotes very detailed diagnostic logs. A six-field state machine whose decisions depend on timing needs at least one debug line per decision in the field.

## 4. Surgical alternative

What cannot be dropped: the base, `replace`, the requested-position base, and the hand-off itself (the STM positions the app already sends every second, `:456-480`, are evidently not what LMS accounts with, so correcting the server needs the `["time",N]` request; and the retry is needed because CometD lags the slimproto socket). What *can* go is the classification machinery — the separate pause flag, the stream-URL check, the 5 s burst window — by making each fact be recorded where it is actually known:

```kotlin
// PositionChangeRequests: record track changes too, then one question remains
fun note(positionSeconds: Int? = null) { ... }          // already exists
fun trackChangeIsRecent() = isRecent() && lastRequestedPosition == null
// ConnectionHelper: sendButtonRequest(Next/Previous), changePlaybackState,
// advanceToPlaylistPosition -> PositionChangeRequests.note(null)

// LocalPlaybackService: one "our stream may not survive" flag, set by the socket-read
// failure, the playback error and pause/unpause alike (no second flag, no window)
val continues = (streamCut || handOffPending || lastWasOurs) &&
    !PositionChangeRequests.trackChangeIsRecent()
streamCut = false; handOffPending = false            // consumed, whatever they were
streamGeneration++                                   // before any suspending call
streamStartPosition = if (continues) previousPosition
                      else requestedPosition?.seconds ?: Duration.ZERO
player.play(..., replace = continues)
lastWasOurs = continues
if (continues) handOff(previousPosition) else adoptIfServerResumed()

// LocalPlayer: a counter for what the *player* starts, not what the server sends;
// it replaces the 5 s burst window: lastWasOurs only stays true while the stream we
// classified as ours is really the item playing (cleared/false once it ends).
override fun onMediaItemTransition(item: MediaItem?, reason: Int) { ...; playbackGeneration++ }
// adopt only when playbackGeneration moved past the value captured at the strm-s

// hand-off: re-check the generation after the fetch, and do not call a throwing API bare
runCatching { connectionHelper.updatePlaybackPosition(slimproto.playerId, sec) }
```

That is ~half the added lines and removes C1 (track changes are known), C3 (the dead check goes) and the `clear()` dance; C5 shrinks to one re-check. Note what replaces `continuationUntil`: the burst continuity, which today needs a 5 s clock, follows from the player-side counter — a burst member is a continuation because the previous one became the playing item, not because it arrived within 5 s. I did not find a *much* smaller change that also resyncs the server. The smallest version that fixes only the upstream symptom — the display resetting to 0:00 after a server restart — is this, and it is worth showing the maintainer as the fallback:

```kotlin
is StreamStart -> {
    val position = playerPosition()
    val requested = PositionChangeRequests.requestedPosition()
    val restarted = player.isPlaying || player.paused   // a live stream was cut under us
    streamStartPosition = if (restarted) position else requested?.seconds ?: Duration.ZERO
    player.play(..., replace = restarted)
}
```

Its limit is C2 in reverse: `player.isPlaying` cannot separate a restarted stream from the next track's pre-sent stream either, which is why the draft's flags exist at all.

## 5. Contract/ripple

- `ConnectionHelper.fetchPlaybackPositionSeconds` (new, `:334-339`): one caller, `LocalPlaybackService.kt:452-453`. Note it conflates "no connection" with "not playing" (C6).
- `LocalPlayer.play(..., replace = false)` (`LocalPlayer.kt:185-228`): one caller, `LocalPlaybackService.kt:549`; no other `play` caller exists, so the default is dead weight.
- `PositionChangeRequests` (new, process-wide, no player id): written by `ConnectionHelper.kt:326` — reached from `SqueezeboxMediaPlayer.kt:142,153`, `NowPlayingFragment.kt:242` and the hand-off `LocalPlaybackService.kt:440` — cleared at `:445`, read at `:502,511`. A seek on *any* player (the now-playing screen can show another one) therefore disarms the local player's continuation for 3 s.
- New package dependency: `localplayer` now imports the cometd layer (`LocalPlaybackService.kt:46`); at `6dacef7` that package had no `connectionHelper` reference at all. slimproto and CometD are independent connections, so every fetch-based guard has a third state: socket fine, control channel down.

## 6. Concurrency/lifecycle

- All service state is touched on the main dispatcher: `handleCommand` runs in the sequential `connectAndRunSlimproto` loop (`:270-296`), and the callbacks launch on `lifecycleScope` (`:245,251,415`), so the fields themselves are safe; coroutines die with the service.
- `PositionChangeRequests` is read from main and written from UI/CometD coroutines through two separate `@Volatile` fields (`PositionChangeRequests.kt:22-23`): a reader can see the new timestamp with the previous position and treat an old seek as current. Write the position before the timestamp, or keep one immutable holder.
- One hand-off per `strm-s` at most, but two overlapping interruptions can start two (C5); each may live ~70 s (10 × (5 s timeout + 2 s)). `StreamStop` (`:593-595`) stops the player and resets none of the state, so a scheduled adopt/hand-off can still land after the stream is gone.
- `handOffPending`/`continuationUntil` are set before the request (`:435-439`); if the request fails, they stay until the next `strm-s` consumes them, which is a 5 s window worth noting rather than a defect.

## 7. Style

- Longest added line is exactly 100 columns (`PositionChangeRequests.kt:9`), measured over `git diff 6dacef7 origin/fix/position-after-disconnect | grep '^+'`; all others ≤99, so the limit holds (`.editorconfig` has no explicit maximum; ktlint's android_studio style caps at 100).
- `LocalPlaybackService.kt:550`: a blank line was added inside the `player.play(` argument list — accidental, remove it.
- The comments are long for what they say and two of them state more than the code knows: `:384` ("time since the server started the current stream, i.e. our position in it") is false for every appended stream, and `:513-518` describes a hand-off rule that is then not applied to the base. Short, factual sentences would also expose C1/C3 while reading.
- No logging in the new paths (see section 3).

## 8. Tests

- The tree has only `model/ListResponseTest.kt` and `service/localplayer/FlacMetadataCachingDataReaderTest.kt`, plain JUnit (`testImplementation(libs.junit)`, `app/build.gradle.kts:137`), no Robolectric and no `testOptions`; a test of `LocalPlaybackService` as written would need a `Service`, `SystemClock`, ExoPlayer and a socket — infrastructure the repo does not have, and saying so is the honest answer.
- What is feasible without inventing anything: extract the decision (`continuesCurrentStream`, the adopt and hand-off conditions) into a pure function over `(now, flags, requestedPosition, isRequestRecent, previousPosition, serverPosition)` and table-test the four cases — blip burst, pause then resume, seek, track change after a pause. The Flac test shows the house pattern (hand-written fake, no Android APIs). That one test would have caught C1 and C3 and is the only place where a unit test pays off here.

## 9. What to ask the maintainer for

- Take the idea, not the shape: base + `replace` + requested-position base are the fix; ask for the classification to be reduced to one "our stream may not survive" flag plus a real record of the requests we send (section 4).
- Delete the stream-URL comparison, or explain the measurement that contradicts the seek PR text (C3).
- Reset the continuation flags when *we* request a track change, and gate the hand-off on that as well (C1).
- Do not adopt a server position for a stream the player has not started yet — tie the adoption to the player's item transition (C2).
- Do not call `updatePlaybackPosition` bare from `lifecycleScope`; and actually retry it, since the whole loop exists for the case where it throws (C4).
- One debug log per decision (restart vs continuation, adopt, hand-off, and why it was skipped) before this ships: the reviewers cannot re-derive the behaviour from a device log today.
- Decide how this relates to `fix/local-position-display`: if that draft lands, the hand-off is only needed for what the *server* does with the position (early track advance), not for what the display shows — which changes how much of this is worth keeping.
