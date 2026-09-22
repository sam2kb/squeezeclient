# Review — position a seek asked for (inside `fix/position-after-disconnect`, `759b462`)

**Reviewed against** upstream `6dacef7`; the change lives in branch tip `759b462`. Read-only review;
no build was run here (see `reviews/README.md`).

**Hunks judged here** (the rest of the tip is `fix-position-after-disconnect.md`'s):
`PositionChangeRequests.kt`; `ConnectionHelper.kt:325-328`; the counter and guards
(`LocalPlaybackService.kt:99,396-400,415-421,532`); the requested-position base and its classification
(`:502,507-512,519,522-529,536-546`); the pause/resume writes (`:565,576`). `fetchPlaybackPositionSeconds`
and `LocalPlayer.play`'s `replace` parameter are core hunks; I only checked how these hunks consume them.

## 1. Verdict

The diagnosis is verifiable from the tree: the old guard compared the stream URI, and the URI is the
request path the server itself sends (`SlimprotoSocket.kt:229-259`) — LMS's `/stream.mp3?player=<mac>`,
identical for every track, so it could never separate two streams. Using the requested position as the
base of the answering stream is the right, smallest idea, and the counter genuinely closes the
stale-adoption hole (2.3). Proportionate: 40 new lines plus three wires, no rewrite. What does not hold
up is the *pairing* between request and stream: the port dropped the calls that mark a track change, and
the slot is process-global and unkeyed, so a request can base a stream that answers another one.

## 2. Correctness

**2.1 The track-change notes are missing — concrete wrong position.** On
`origin/integrate/upstream-favs-sync-fix` the next/prev seek paths call `PositionChangeRequests.note()`
(`SqueezeboxMediaPlayer.kt:154,163` there); on `759b462` the only `.note(` call in `app/src/main` is
`ConnectionHelper.kt:326`. So at `LocalPlaybackService.kt:536-540`, after "seek to 200 s, then Next
within 3 s" (`WINDOW_MS = 3_000`, `PositionChangeRequests.kt:12`), `isRecent()` is still true and
`requestedPosition()` returns 200: the *new* track's stream gets base 200 s, and because
`requestedPosition != null` the correcting adoption is skipped (`:544`). The PR text says next/prev "keep
passing nothing" — true on the source branch, false here. The in-app buttons
(`NowPlayingFragment.kt:256-257` → `bindToRequest`, `:399-403`) never noted even there, which argues for
noting in `sendButtonRequest` (section 4).

**2.2 The slot is unscoped, and it survives a failed request.** `note()`/`requestedPosition()` carry no
player (`PositionChangeRequests.kt:21,33`), while the writer takes any `playerId`
(`ConnectionHelper.kt:325`; callers `SqueezeboxMediaPlayer.kt:142,153`, `NowPlayingFragment.kt:242`) and
the reader is the local player only (`LocalPlaybackService.kt:502`). *Hypothesis* (needs the local player
streaming): a seek on another player bases the local player's next stream start; everything else here is
player-scoped (`:453`, `:440-442`), so the asymmetry looks unintended. Relatedly, `note()` (`:326`)
precedes `publishOneShotRequest` (`:327`), which throws with no client (`ConnectionHelper.kt:392-393`) and
cancels the caller after a Cometd failure (`:417-423`) with nothing clearing the note: once the cometd
draft makes that failure survivable, an un-applied position stays valid for 3 s and a server stream start
reads it (`:538`). Fact: the ordering; *hypothesis*: the exposure. Recording after the publish is no
option — the server starts the stream while answering — so the fix is `clear()` on failure.

**2.3 Stale adoption: closed, as far as I can find.** The increment (`:532`) is on the same path as
`streamStartRealtime`/`streamStartPosition`/`currentStreamUri` (`:531-546`) with no suspension between; the
handler runs on one Main-dispatched loop (`:270-291`, sole `handleCommand` call at `:286`); both delayed
coroutines capture and re-check the counter (`:396-400`, `:419-421`); and no stream can bypass it — the
only stream creation is `LocalPlayer.play` (sole caller `LocalPlaybackService.kt:549`;
`setMediaSource`/`addMediaSource` only inside it, `LocalPlayer.kt:222,226`). `lifecycleScope` is
`Main.immediate`, so the capture is synchronous at the call site; I looked for that race and it is not
there. This part holds up.

**2.4 The guard also aborts work that is still valid, and the pause flag is sticky.** The counter counts
every stream, including one the server appends for the next playlist item while the current item plays
(`LocalPlayer.kt:162-167`, append path `:219-226`); that start leaves the playing item unchanged but
invalidates a pending adoption (2 s) or hand-off retry (10 × 2 s, `:647-651`), so the position of the
stream that is *still playing* never gets corrected (*hypothesis*: needs such a start in that window; the
conflation "my stream was replaced" vs. "an item was appended" is real either way). And
`streamResumesAfterPause` is set on pause and unpause (`:565,576`) but cleared only by the next stream
start (`:528`), so a pause that is never resumed labels whatever stream arrives next as pause-resumed: base
`previousPosition` (`:537`) plus a hand-off (`:519`). It should mean "the answer to this pause".

**2.5 Racing seeks: no lasting defect.** Last-write-wins gives both stream starts the later position; the
superseded stream is replaced immediately and `playerPosition()` only feeds `sendStatus` (`:460`), so the
end state is correct. A "consume on use" variant would be *worse*: the second stream start would find the
slot empty and reset the base to zero (`:539`).

## 3. Over/under-reach

- `PositionChangeRequests` describes intent this port does not deliver: "or switch track" (`:6`), the
  default argument (`:21`), `clear()`'s "requests the local player sends itself" (`:35`).
- `currentStreamUri` (`:96`, set `:531`, compared `:512`) cannot distinguish streams for one player; the
  counter replaced exactly that comparison, so the leftover should go or say what it really guards.
- Otherwise nothing is broader than needed; the 5 s / 10 min comparison (`:637-645`) is what keeps the
  pause/resume hand-off free in the normal case.

## 4. Surgical alternative

Keep the mechanism, remove the guesswork:

```kotlin
// ConnectionHelper.kt — covers the media session (SqueezeboxMediaPlayer.kt:129,135) and the
// in-app buttons (NowPlayingFragment.kt:256-257), unlike the source branch's call-site notes.
suspend fun sendButtonRequest(request: PlaybackButtonRequest) {
    when (request) {
        is PlaybackButtonRequest.NextTrack, is PlaybackButtonRequest.PreviousTrack ->
            PositionChangeRequests.note()   // a track change is not a continuation
        else -> Unit
    }
    publishOneShotRequest(request)
}
```

- key the slot by `PlayerId` (one map instead of the two volatile fields), using the id
  `updatePlaybackPosition` already has;
- `clear()` when the publish fails (`catch (e: Exception) { PositionChangeRequests.clear(); throw e }`);
- decide what a next-item `strm-s` (appended, playing item unchanged) does with a recent request — today
  it takes the `requestedPosition` arm and skips the adoption.

On the brief's question, "record the requested position only, and trust the existing adoption path": no.
The adoption takes the *server's* position, and the PR's own device log (stale adoption, `12:16:25.555`)
is a case where that position was wrong for the stream; using it as the base after a seek reintroduces the
reported bug. The recorded position has to be *used*, as `:538` does. The counter needs no smaller form
either.

## 5. Contract/ripple (checked on `759b462`)

- `PositionChangeRequests`: written at `ConnectionHelper.kt:326` and `LocalPlaybackService.kt:445`, read at
  `:502` and `:511`; no other reference in `app/src/main`.
- `updatePlaybackPosition` callers: `SqueezeboxMediaPlayer.kt:142,153`, `NowPlayingFragment.kt:242`,
  `LocalPlaybackService.kt:440` — none catch, so all four now also write the global slot.
- `fetchPlaybackPositionSeconds`: `LocalPlaybackService.kt:453` only, safe via `runCatching`
  (`ConnectionHelper.kt:334`). `LocalPlayer.play(..., replace)`: sole caller `:549`.
- `PositionChangeRequests` is an `object` and is not cleared in `onDestroy` (`:184-191` stops the player
  only), so a service restart within 3 s inherits a stale slot; `streamGeneration` restarts with it.

## 6. Concurrency/lifecycle

Single-threaded handling plus `Main.immediate` capture (2.3) mean the guard is not racy, and a later stream
start aborts an earlier hand-off loop, so duplicates cannot pile up. One leak: when the hand-off's publish
cancels (`ConnectionHelper.kt:417-423`), `clear()` (`:445`) is skipped and its note stays — normally
masked by `handOffPending` (`:439`). The two `@Volatile` fields (`PositionChangeRequests.kt:14-19`) allow a
torn read (new timestamp, old position); one instruction wide, so cosmetic.

## 7. Style

Measured from `git diff -U0`: every added line is ≤ 100 columns, the maximum being
`PositionChangeRequests.kt:9`. Comments are short and factual in the house tone. Two nits: the blank line
added inside the `player.play(` call (`LocalPlaybackService.kt:550`) is diff noise, and the port adds no
`Log.` lines at all (`git diff | grep -c "^+.*Log\."` → 0) although the PR text quotes `strm-s …
requested=68` and `hand-off check: …` lines that exist on the integration branch only.

## 8. Tests

Only `ListResponseTest.kt` and `FlacMetadataCachingDataReaderTest.kt` exist; there is no Robolectric or
instrumentation setup, and `PositionChangeRequests` uses `SystemClock` while the service needs ExoPlayer
and a lifecycle. A test is neither warranted nor cheap; the useful artefact is the diagnostics (9).

## 9. What to ask the maintainer for

- Put the track-change `note()` in `sendButtonRequest`, or state that the port intentionally drops it — as
  it stands the class doc and the PR text promise a case the code does not handle.
- Say whether the request slot is meant to be per player; both ends are already player-scoped.
- Port the diagnostics, or paste a log from a build of *this* branch: the PR's verification was produced
  on the integration branch, and this port logs nothing.
- Decide what an appended (next-item) stream start does with a pending request, and whether
  `streamResumesAfterPause` should stay sticky.
- Drop `currentStreamUri` and the blank line at `:550` while touching this.
