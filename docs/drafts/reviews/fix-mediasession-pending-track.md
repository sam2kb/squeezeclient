# Review — `fix/mediasession-pending-track` (`4f3caf6`)

**Reviewed against** upstream `6dacef7`, branch tip `4f3caf6`, diff 1 file +35/−8. Read-only review; no build was run here (see `reviews/README.md`).

Upstream has not drifted (`gh api repos/maniac103/squeezeclient/commits/main` → `6dacef7`); line numbers are
the branch's, added lines measured arithmetically.

## 1. Verdict

Diagnosis right, place right, size right — lifetime rule wrong. The metadata has to follow the optimistic
index and the pending state is where it belongs, but the draft ties the *whole* pending change (offset
included) to a song-equality test and anchors `songAtOffset()` on the already-applied position. The author's
suspicion is half right: the pending state cannot get stuck (the 3 s revert always fires), but "wait for the
server to confirm the song" is the fragile part, and on a wrong guess it *extends* the wrong-metadata window.

## 2. Correctness

**What holds up.** On the intended path (one press, server does what was predicted) the change does what the
PR says: `:176` publishes the predicted song, `:187-188` the optimistic index, and statuses for the
still-current revision no longer flip the metadata back. The comparison is plausible rather than accidental:
`PlaylistItem` is a data class (`model/Playlist.kt:33-40`) and both sides are built by the same parser
(`cometd/response/PlayerStatusResponse.kt:100`, `:152-170`) via the same request class
(`ConnectionHelper.kt:265-268`). Nothing leaks — `:322-328` clears the state 3 s after the last
`updateUnacknowledgedState()`, server or not, and cancelling that job when the change is kept (`:429-431`) is
correct: it must stay armed.

**Defect 1 — a second press predicts the wrong track.** `songAtOffset()` reads the *applied* position
(`:279-283`) while `updateUnacknowledgedState()` accumulates offsets (`:302-303`), so two Next presses inside
the window (key-hold repeat, double tap) set `playlistPositionOffset = 2` with the song of item `applied + 1`:
index N+2 published with N+1's metadata (`:187-188`, `:193-196`) — the same mismatch this PR removes, sign
flipped. Verified by code reading; the author's single-press evidence does not cover it.

**Defect 2 — the offset is added twice when the guess is wrong.** `applyPlayerState()` clears only on a song
match (`:429-431`), so whenever the applied state moves without that song arriving — defect 1, a Next pressed
just before a track ends on its own, a request the server ignores or redirects — the pending offset survives
and `getState()` adds it on top of the position the server just reported (`:187-188`). Published: an
over-counted index (at the end of the queue, past the last item — the case the sibling draft
`fix/mediasession-next-at-playlist-end` reports as media3 rejecting with *currentMediaItemIndex must be less
than playlist.size()*) plus the metadata of a track that is not playing, for up to 3 s — longer than the
~1.3 s flap being fixed. Arithmetic verified; **hypothesis** (no build): whether media3 throws here or
silently accepts the index. Related **hypothesis**: `PlaylistItem` equality includes `actions`
(`model/JiveActions.kt:26-40`) from two server pages that could disagree, in which case the change never
matches and survives to the 3 s revert with the same over-counted index. The author's check ("only the new
song is published") cannot distinguish a match from the 3 s revert.

**Defect 3 — a song is inherited by unrelated predictions.** `song ?: unacknowledgedStateChange?.song`
(`:293`) is right for an accumulating offset, wrong for a song: `COMMAND_SEEK_TO_MEDIA_ITEM` (`:151-161`)
sets an absolute position and no song, so a queue-item jump inside the window publishes the previous Next's
song at the jump target (`:184-185`), and the `getOrNull` null at the end of the playlist keeps it too. That
path was correct before the draft.

## 3. Over/under-reach

Over: `:427-428` promises "keep reporting the pending song", but the code keeps the whole
`UnacknowledgedPlayerStateChange` alive — that is defect 2 — and replaces the *event* upstream `caf5e3c`
uses ("a status was applied") with a *value* comparison that can be wrong in both directions. Under:
duration and position still come from the applied state (`:180-181`, `:250-253`), so a Next pressed at 3:00
publishes the new title with the old track's duration and elapsed position — one mismatch traded for another;
the PR text should say so. The 6-line KDoc (`:273-278`) and `:427-428` explain the same thing twice.

## 4. Surgical alternative — smaller, and no song to store

`songAtOffset(1)` is exactly `playlist.items[appliedPosition + 1]` and `getState()` already walks that list
with `currentPosition = appliedPosition + pendingOffset` (`:192-204`), so the item to publish is at hand; only
the *lifetime* needs fixing — keep the move while the applied track position is still the one the prediction
came from, drop it when the server's position includes it.

```kotlin
// UnacknowledgedPlayerStateChange gains basePosition, set where the prediction is created (:311-319):
val basePosition: Int? = null,   // playerState.playlistPosition when the move was predicted

private fun applyPlayerState(newPlayerState: PlayerState) {
    val pending = unacknowledgedStateChange
    playerState = newPlayerState
    // A move stays pending until the server's position (which then includes it) arrives; everything
    // else keeps the old behaviour of ending at the first applied status.
    if (pending?.basePosition == null || pending.basePosition != newPlayerState.playlistPosition) {
        unacknowledgedStateChange = null
        unacknowledgedStateRevertJob?.cancel()
    }
    invalidateState()
}
```

and in `getState()` (`:192-198`) take the current item from the list while a move is pending:

```kotlin
val movesPosition = unacknowledgedChange?.absolutePlaylistPosition != null ||
    unacknowledgedChange?.playlistPositionOffset != null
val builder = if (index + list.offset == currentPosition) {
    // While we move optimistically, the status metadata still describes the previous revision.
    val song = if (movesPosition) item else currentSong
    song.toMediaItemDataBuilder(index).apply { currentSongDurationUs?.let { setDurationUs(it) } }
}
```

Same result on the intended path, but it cannot go stale (the item is the one the published index points at),
needs no equality test, no `songAtOffset()`, no new parameter on `updateUnacknowledgedState()`, covers
`COMMAND_SEEK_TO_MEDIA_ITEM`, and defects 1-3 disappear: once the applied position differs from
`basePosition`, the server's own state is published. In-track seeks and play/pause are untouched
(`basePosition == null`); the sibling draft's end-of-list clamp is still needed.

## 5. Contract/ripple

- `getState()` (`:173`) is the only implementation, built once at `MediaService.kt:81`, wrapped in a media3
  `MediaSession` at `MediaService.kt:132`. No media client exists in `app/src/main` (grep); the manifest
  advertises the browse service (`AndroidManifest.xml:74`), so consumers are external browsers and the
  notification (`MediaService.kt:85`) — all read index and metadata together, which is why the pair is the
  contract, not either value.
- Writers: `handleSeek` (`:124-165`), `handleSetPlayWhenReady` (`:114-122`), `handleStop` (`:167-171`);
  readers: `getState()` (`:176`, `:184-188`, `:240`, `:251`). None depends on the `song` field, so §4 is
  local to this file.
- `fix/local-position-display` inserts at the top of `applyPlayerState` and in `setContentPositionMs`
  (`git diff 6dacef7 origin/fix/local-position-display -- .../SqueezeboxMediaPlayer.kt`), so the reported
  conflict is real in any form of this fix. `fix/mediasession-next-at-playlist-end` clamps `currentPosition`:
  merged, defect 2's index is clamped rather than rejected, but the published row is still wrong, so the
  drafts do not cancel out.

## 6. Concurrency/lifecycle

`unacknowledgedStateChange` is written only at `:311`, `:326` and `:430`, all on the main looper
(`SimpleBasePlayer` application looper; `future {}` on the lifecycle scope): no new race. Neither disconnect
nor a player switch clears it — `isConnectedToServer = false` (`:83-95`, from `MediaService.kt:237`) and a
player change (`:66-72`, from `MediaService.kt:105`, `:154`) only cancel the status subscription
(`:351-363`) — so inside the window the previous player's predicted song is still published (with
`STATE_BUFFERING` on disconnect, `:236`). The revert job bounds it, but every call re-arms it (`:322-328`),
so a burst of presses extends the window over a target that is already gone.

## 7. Style

Added lines are ≤ 98 columns (longest: the KDoc at `:274`/`:276`); the file's longest line stays 100 (`:436`,
unchanged). No `.editorconfig` sets `max_line_length` (only `ktlint_code_style = android_studio`,
`.editorconfig:1-2`); ktlint's android_studio budget is 100 — fine. Diff hygiene is good: only the described
lines, no reformatting, argument order and trailing commas match the file. Tone is the weak point: the file's
own comments state the mechanism in a few factual lines (`:194-195`, `:369`, `:379-380`), while the KDoc
reads as a bug history ("which makes head units flip back to it and then forward again"). Naming: the file
says `currentSong`/`nowPlaying` (`:464`, `model/PlayerStatus.kt:83`), so `pendingSong` over `song`.

## 8. Tests

At `6dacef7` the tree has exactly `ListResponseTest.kt` and `FlacMetadataCachingDataReaderTest.kt`
(`git ls-tree -r --name-only 6dacef7 -- app/src/test`), and the only test dependency is
`testImplementation(libs.junit)` (`app/build.gradle.kts:137`) — no Robolectric, no mocking library. The logic
is private state driven by media3 callbacks on a `Looper` inside a class needing `Context`,
`ConnectionHelper` and `Lifecycle`, so a unit test means inventing infrastructure that does not exist; none is
warranted. A log-based check is: press Next twice inside a second and print the *published index*, not only
the title, which is what the author's evidence misses (defects 1-3). Extracting the decision into a pure data
holder (`advance()` / `isConfirmedBy(state)`) would make it testable with the JUnit already present.

## 9. What to ask the maintainer for

- Take the insight (metadata must follow the optimistic index), not the clear rule: the equality test plus
  the retained offset is what can publish a wrong index and a wrong track for up to 3 s.
- Prefer the smaller form in §4 — fewer lines, no equality test, same intended behaviour.
- If the draft is kept: anchor `songAtOffset()` on the pending position, drop the offset once the applied
  position has moved, do not inherit a song into a jump prediction, and keep the equality rule only with a
  log line proving it fires.
- Decide and state whether duration/position may still describe the previous track while the song is
  optimistic (`:180-181`, `:250-253`).
- One comment, not two; describe the invariant, not the history.
