# Review — `fix/slider-drag` (`3d4c247`)

**Reviewed against** upstream `6dacef7`, branch tip `3d4c247`, diff 1 file +95/−5. Read-only review; no build was run here (see `reviews/README.md`).

Upstream has not drifted (`gh api repos/maniac103/squeezeclient/commits/main` → `6dacef7`). Line numbers are the branch's (`git show
origin/fix/slider-drag:.../ui/nowplaying/NowPlayingFragment.kt`, cited `NowPlayingFragment.kt`); added lines measured arithmetically. Material claims come from the pinned 1.14.0
(`gradle/libs.versions.toml:23`), read from `BaseSlider.java` fetched read-only from the material-components 1.14.0 tag.

## 1. Verdict

Diagnosis and placement are right, the size is proportionate: `progressSlider.value` has exactly four writers, all here (`:481`, `:485`, `:507`, `:515`), and sending the seek on touch end
has in-tree precedent (`SliderBottomSheetFragment.kt:32,49-61`). Two things need hardening before upstream — `userSeeking` is a flag Material can leave set, and the hold uses a second,
weaker notion of "the song changed" than `:293`. No rewrite; the insight is the deliverable.

## 2. Correctness

**Holds up.** The crash path is closed at every write: `valueTo` is assigned only at `:476`/`:516`, each followed in the same synchronous block by a fitting value (`:480-486`, `:504-508`,
`:515`), `valueFrom` never moves (`:247`); Material's `setValueTo` only invalidates (`BaseSlider.java:855`) and the throw comes later at draw (`:2767-2769` → `:794-796` → `:737`), so the
clamp is both necessary and safe. The hold is armed before the first user-driven change (`onStartTrackingTouch` `BaseSlider.java:3380` precedes `snapTouchPosition` `:3382`) and both write
paths check it (`:480`, `:506`). A song change mid-drag keeps the drag, clamps to the new duration (`:482-486`) and arms the hold for the new song because `pendingSeekSong` is read from the
`currentSong` field (`:600`), already advanced by the collector (`:293-294`); seeking in the song current at release is not new — the old delayed path sent the same `value`.
`MainActivity.kt:387` replaces the fragment per player, so nothing leaks across players.

**Defect 1 — `userSeeking` can be left set, freezing the bar.** Only `onStopTrackingTouch()` clears it (`:254`), but Material skips that callback while the Slider is disabled: `onTouchEvent`
returns at `BaseSlider.java:3342-3344`, before the ACTION_UP/ACTION_CANCEL switch, and `setEnabled()` (`:2530-2534`) does not stop tracking. `update()` disables the slider on a status update
(`:487`, `:517`) — reachable with a finger down when the queue's last track ends (`Stopped`) or the new song reports no duration. Lift-off then leaves the flag set: `canApplyPosition`
returns false at `:626-628` for good, and thumb and elapsed time stop following playback until a later completed drag. Same hole if the view is detached mid-drag
(`BaseSlider.java:2589-2600`), and nothing resets the four fields outside the listener. Verified from Material 1.14.0; edge case, cure is 3 lines (§4a). The one unguarded write, `value = 0F`
at `:515`, is the same entry: it needs a finger down and disables the slider in that update.

**Defect 2 — a seek inside the tolerance is not held.** `SLIDER_SETTLE_TOLERANCE = 3F` (`:640`) is compared with the reported position, and the first status after a seek still carries the
pre-seek position: seek forward 2 s while playing and `|newValue − pending| ≈ 1.5 ≤ 3` settles the hold (`:630-634`), so the thumb still jumps back. Large seeks are fixed, small ones only
reduced; no position comparison can tell a stale status from a settled one inside the tolerance, so say this in the PR text rather than tightening the number (that would release large seeks
on stale data).

**Defect 3 — equal tags are not a song change here.** `:615-618` compares title/artist/album, so a repeated track, or two entries with the same tags on different albums, keeps the hold and
the thumb sits at the old position clamped to the new duration (`:485`) until the 5 s timeout — the follow-up case the PR describes survives there. The limit is in the data, not the patch:
`Playlist.PlaylistItem` (`model/Playlist.kt:33-40`) has no id, only tags plus `actions`/`iconId`/`icon`. But the fragment now has two notions of "song changed" — full data-class equality at
`:293`, tags at `:615-618` — which disagree for two tracks with equal tags and different artwork ids.

## 3. Over/under-reach

- **Under-reach, not a defect:** only this screen's slider arms the hold, so a media-session seek (`SqueezeboxMediaPlayer.kt:142`, `:153` — head unit, notification, keys) still bounces. The PR
  text should claim the screen's own drags, not "the seek".

- **Comment promises what the code does not:** `:264-265` says a touch is "handled when the touch ends", but the `fromUser` branch has no such condition — a drag paused >200 ms still sends a
  seek mid-gesture and re-arms the hold with a mid-drag target (overwritten at release). Add `&& !userSeeking` (safe: the release path covers every touch case) or drop the sentence.

- **Pre-existing, so not charged to this diff:** the release seek is unconditional, so a cancelled gesture seeks the value Material snapped back to at ACTION_CANCEL (`BaseSlider.java:3440`,
  snapshot from DOWN, `:3579-3587`) — the drag-start position; that snap dispatches `fromUser` (`:3563-3577`), so the old code sent it too.

- Nothing broader than the problem: no API, layout, string or dependency change; one file, one commit. `progressMinimized` is not held (`:490-491`, `:509`) but is never visible with the slider
  (`nowplaying_motion_scene.xml:215,236,437,457`), so it is consistent.

## 4. Surgical alternative

**(a) Make the flag unstickable — 3 lines, at `:487` and `:517`** (clearing on disable loses no seek: Material would not deliver the release either):

```kotlin
val enabled = status.playbackState != PlayerStatus.PlayState.Stopped
if (isEnabled != enabled) {
    isEnabled = enabled
    if (!enabled) userSeeking = false // a disabled Slider skips onStopTrackingTouch (BaseSlider:3342)
}
```

One more field makes the release seek mean what `:264-265` says (and suppresses the pre-existing cancel-path seek): record `trackingStartValue = slider.value` on start, seek on stop only `if
(slider.value != trackingStartValue)`.

**(b) One home for the guard** — replaces `:479-486` and `:504-508`, keeps "stay in range" with the write, and shrinks the rebase on `fix/local-position-display` (which edits exactly these
two sites) to one hunk:

```kotlin
/** Applies a reported position unless the user drags the slider or one of our seeks is settling. */
private fun applyPosition(positionSeconds: Float) {
    val slider = binding.progressSlider
    val newValue = positionSeconds.coerceIn(0F, slider.valueTo)
    when {
        canApplyPosition(newValue) -> slider.value = newValue
        slider.value > slider.valueTo -> slider.value = slider.valueTo // Slider throws like this when drawn
    }
}
```

**(c) One notion of "song changed":** `dropStaleSeek` becomes `if (pendingSeekSong != currentSong) { pendingSeekPosition = null; pendingSeekSong = null }` — 14 lines to 4, and it picks up
`iconId`/`actions`. It must stay in `update()`: clearing in the collector's branch (`:293-296`) would run after `update()` (`:291`) and still hold the new song's first position back for one
status.

**(d) Dead ends.** `slider.isFocused` fails: `BaseSlider` calls `requestFocus()` on ACTION_DOWN (`BaseSlider.java:3377`) and never gives focus up, so after the first touch the bar would
follow nothing. A pure timeout expires under a held finger and pushes the thumb back; the touch listener is what makes the drag gate exact. For seeks inside the tolerance no comparison helps
(Defect 2).

## 5. Contract/ripple

- Four new private fragment fields (`:102-110`), same lifetime as `currentSong` (`:100`); no public API or resources. `git diff --stat 6dacef7 origin/fix/slider-drag` → 1 file, +95/−5; `git
  log --oneline 6dacef7..origin/fix/slider-drag` → one commit (`3d4c247`).

- All four writers of `progressSlider.value` are in this file (`git grep -n progressSlider`); no other file reads the slider or the new fields. The touch listener displaces nothing — the
  change listener stays (`:261`) and Material keeps touch listeners in a list (`BaseSlider.java:317`, `:1213`); the only other user of the idiom is `SliderBottomSheetFragment.kt:32,49-61`.

- Seek path untouched (`ConnectionHelper.kt:324-325`; other callers `SqueezeboxMediaPlayer.kt:142`, `:153`); one layout, no XML change.

## 6. Concurrency/lifecycle

- All writers/readers of the new state are on the main dispatcher (`lifecycleScope` `:256`, `:267`, `:497`; collector `:286-299`), so the plain `var`s need no synchronization. `seekTo` arms
  the hold before the request (`:598-601`) — right, since `updatePlaybackPosition` can suspend; `SystemClock.elapsedRealtime()` (`:599`, `:631`) is the correct monotonic clock (`git grep -n
  elapsedRealtime` → only these two lines in the tree).

- Fragment-scoped, not view-scoped: nothing resets the fields when the view goes (no `onDestroyView`; `ViewBindingFragment.kt:27-46` neither clears `binding` nor hooks one). The hold
  self-clears in 5 s; the flag does not (Defect 1).

- The seek path no longer cancels the ticker (the old `timeUpdateJob?.cancel()` is gone). Consistent now that the ticker's write is gated at `:506`, but the ticker runs in the fragment scope
  (`:497`) and can outlive the view — pre-existing, harmless here. One gap is Material's: if DOWN looks like a scroll start, tracking begins only on MOVE (`BaseSlider.java:3361-3368`), so a
  status there can move the thumb — invisible, the slider is alpha 0 while collapsed (`nowplaying_motion_scene.xml:457`).

## 7. Style

- Longest added line is 99 columns (`:605`) — `git diff 6dacef7 origin/fix/slider-drag | grep '^+' | grep -v '^+++' | sed 's/^+//' | awk '{print length}'` — within the 100-column limit; no
  tabs, no trailing whitespace.

- Comments read like the file's own voice (`:592-596`, `:604-608`, `:624`); only `:264-265` oversells (§3). The commit message omits that the debounce stopped cancelling `timeUpdateJob`
  (`:467`) — worth a clause, since readers will ask whether that was load-bearing (it was; `:506` replaces it). `dropStaleSeek(...)` shadows the field at `:100` as `update()` does at `:434`
  — house pattern, but worth knowing when reading `:434`.

## 8. Tests

None are feasible with what the tree has, and asking for one means inventing infrastructure: `app/build.gradle.kts:137` has only `testImplementation(libs.junit)`, no
Robolectric/mockito/coroutines-test, no `app/src/androidTest`, and the two existing tests (`ListResponseTest.kt`, `FlacMetadataCachingDataReaderTest.kt`) are pure model tests. Driving this
needs Material's touch protocol plus a Fragment whose `connectionHelper` comes from `(applicationContext as SqueezeClientApplication)` (`extfuncs/ContextExtensions.kt:35-36`), with no seam
to inject. The device sequences in the PR text are the right evidence; if a test is wanted later, lift the hold into a plain class (`requestSeek(pos, song, now)` / `onStatus(...)` /
`shouldApply(...)`) so it is JUnit-testable — a refactor, not the smallest change.

## 9. What to ask the maintainer for

- Take both fixes; ask for the 3 lines that keep `userSeeking` from sticking (§4a) and, if `:264-265` is meant literally, `&& !userSeeking` on the debounce.

- Ask which "song changed" test is canonical (Defect 3 / §4c) and whether the LMS status exposes a track id or playlist index — without one, a repeated track is invisible to every version of
  this fix; and whether the hold is worth its state for seeks ≤ 3 s.

- Optional, but it lowers merge cost: the single `applyPosition()` helper (§4b), which also makes the `fix/local-position-display` conflict a one-hunk rebase. Two sentences for the PR text:
  the bounce is fixed for drags on this screen only, and the minimized bar deliberately shows the un-held position.
