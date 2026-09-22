# Review — `fix/volume-device-volume-fades` (`ba7f766`)

**Reviewed against** upstream `6dacef7`, branch tip `ba7f766`, diff 1 file +61. Read-only review; no build was run here (see `reviews/README.md`).

**Verdict.** The diagnosis is right, the file is the right place, and the load-bearing idea — in the modes that drive the device volume, the device volume is what the user chose and must be re-adopted before any server value is applied again — holds up and is not something upstream does today. Two halves of the implementation do not: the "play the ramp" branch writes the server's *absolute* fade value into `player.volume`, a gain stage these modes never used and nothing ever restores, and a discarded volume is discarded for good rather than deferred. 61 lines in one file is proportionate; the 2 s timer is the least defensible part of it.

## 1. Correctness

Holds up, on the paths the PR names: pause/resume in `DeviceWhilePlaying` (the toggle adopts the device volume, `LocalPlayer.kt:325`, `:351-363`, so the fade no longer becomes `lastSetVolume`; the measured "9 stayed 9" is consistent with the code); stream restart after a blip (while buffering `isPlaying` is false at `:100` and the second predicate matches at `:375`, and nothing re-applies the value later — `updatePlayerVolume` is only called with `isSetVolume = false` from `:316`/`:329`, which in `Device` mode applies nothing, `:386-409`); next track (`onMediaItemTransition` writes only `player.volume`, `:288`).

**The fade is played into the wrong knob, and left there** (hypothesis, reasoned from the code; not measured). `player.volume` is ExoPlayer's own output gain, applied on top of the device volume. In the two device modes the base only ever sets it to `playerInternalVolume * currentReplayGain` with `playerInternalVolume` fixed at `1F` (`:119`, `:288`); the only other assignment is the `PlayerOnly` branch (`:388`). The fade branch (`:105-113`) writes both fields from the server's fade value in **all** modes, and nothing restores them:

- after a resume ramp the output is device volume × server volume; device 9/15 with the server at 40 % gives 0.6 → 0.24, about −8 dB — invisible to a test that watches the device volume index;
- a later `audg` goes through `updatePlayerVolume(true)` (`:115`), which in `Device`/`DeviceWhilePlaying` never touches `player.volume` (`:392-403`), and the next track restores the contaminated `playerInternalVolume` (`:288`); if the last fade value was 0, the stream stays silent at a device volume that is up;
- one-line check for the maintainer: log `player.volume` before/after a resume — if it is not ≈ the ReplayGain, this is real.

**A discarded change is never re-applied** (verified from the code). Mode `Device` (`PreferenceExtensions.kt:83`), user pauses (`strm-p` → `LocalPlaybackService.kt:382-384`), then raises the volume in the app's own volume UI, which goes to the server (`VolumeFragment.kt:63` slider, `:108` keys → `connectionHelper.setVolume`) and returns as `audg` (`LocalPlaybackService.kt:358`). The guard matches (`:375`), the value reaches only `player.volume` (`:110-111`), `lastSetVolume` stays (`:114`), `updatePlayerVolume` is not called. On resume, `adoptDeviceVolume()` returns early in `Device` mode (`:352-355`) and `updatePlayerVolume(false)` (`:329`) has no branch for it — the device volume keeps the old value while the app's UI (which shows the server's volume, `VolumeFragment.kt:90`) and the actual gain both moved. Before the draft the value *was* applied (`isSetVolume && mode == Device`, `:392-394`), so this is a regression in one of the two modes the PR claims to fix.

Residual, not shown either way: a fade step the server sends *before* the local toggle (still `isPlaying`, outside the window) matches neither predicate and still reaches the device volume. The successful live test suggests LMS ramps after its pause/unpause packet, but the fix rests on that ordering and the PR text does not state it.

## 2. Over/under-reach

- `!isPlaying` (`:375`) classifies *every* server volume while paused or buffering as a fade. Deliberate per `:365-369`, but the consequence is not in the PR text: in both device modes the app then never applies the server's mixer volume while not playing and never re-syncs it later. Either re-apply it on resume, or push the adopted device volume to the server (`connectionHelper.setVolume`, as the media session does at `SqueezeboxMediaPlayer.kt:92`); otherwise the app's own slider disagrees with the device indefinitely.
- `adoptDeviceVolume` rewrites `lastSavedDeviceVolume` (`:360-362`) *before* `updatePlayerVolume(false)` restores it (`:405-408`). At the pause toggle the saved value becomes the current (playing) device volume, so the restore writes the same value and upstream's "give the system volume back while not playing" goes silently dead in `DeviceWhilePlaying`. Intended? The PR text does not say.
- The PR text claims adoption in "`device`/`devicewhileplaying`", but `:352-355` admits only `DeviceWhilePlaying`. In `Device` mode an external change is neither adopted nor applied while not playing, and the next server volume overwrites it.
- The reason at `:353-354` is wrong for `Device`: that mode *does* translate the app volume into the device volume (`:392-394`); only the adoption is skipped.
- `onDeviceVolumeChanged` (`:332-338`) adopts every reported change, including the app's own `setDeviceVolume` writes, which the older branch filtered. Mostly idempotent (the float→int round trip is stable), but it also refreshes `lastSavedDeviceVolume`. Minor.
- `Log.d` at `:337` logs the callback parameter, not the adopted value; the two can differ.

## 3. Surgical alternative

If the ramp does not have to be audible in the device modes, make the guard mode-specific and apply the fade to nothing — that removes both findings above:

```kotlin
set(value) {
    // The server ramps its volume around play state changes and announces its mixer volume when
    // a stream is set up or stopped. In these modes the device volume is the user's, so that
    // value is applied to nothing; the device volume must not follow the ramp.
    if (prefs.localPlayerVolumeMode != LocalPlayerVolumeMode.PlayerOnly && isServerVolumeFade()) return
    lastSetVolume = value
    updatePlayerVolume(true)
}
```

Everything else in the draft (adoption in `onPlayWhenReadyChanged`, `onDeviceVolumeChanged`, the second predicate) stays; `isServerVolumeFade` then needs no mode check of its own, and `PlayerOnly` keeps the plain upstream behaviour. The cost is an inaudible fade in the device modes, which is what already happens whenever the device volume is applied — worth one sentence in the PR text. If the ramp must stay audible, scale the *existing* volume instead of replacing it: keep the last real server value and apply `value / lastServerVolume` as a factor to `playerInternalVolume * currentReplayGain`, never writing `playerInternalVolume`. That is `applyVolumeRamp` on the older branch (`git show origin/fix/volume-follow-device-changes:app/.../LocalPlayer.kt`, lines 497-502), and it is exactly what prevents the permanent offset.

On the timer: `PAUSE_FADE_WINDOW_TIME = 2000L` (`:482`) is ~6× the ~0.3 s ramp the PR text quotes, and it re-arms on *every* `playWhenReady` change (`:322`), including `play(..., autoStart)` (`:234`) and ExoPlayer's own end-of-item change. Cheaper keys, in order of preference: (a) drop it if a log shows the ramp always completes while `!isPlaying`; (b) key on the burst instead — a second `audg` within a few hundred ms means ramp — which is what the older branch did with `SERVER_VOLUME_CHANGE_DELAY = 350L` and needs no toggle timing; (c) check whether LMS flags ramps, since the `audg` packet already carries an unused `digitalVolume` flag and `preamp` byte (`SlimprotoSocket.kt:105-110`, parsed at `:162-172`, both ignored at `LocalPlaybackService.kt:358`). (c) is a hypothesis — I could not inspect LMS's sender here.

**What the older branch had that this one dropped.** Dropping `applyVolumeRamp` is the direct cause of finding 1; dropping the burst coalescing is why every `audg` now reaches the device volume immediately; dropping `setDeviceVolume`'s "skip a write that does not change the value" guard (older branch lines 522-531, with the author's own note that Bluetooth units report such writes and pop their volume slider) means the draft writes the same device volume back at every pause (`adoptDeviceVolume` → `:405-408`), exactly the case that guard covered; the self-change filter in `onDeviceVolumeChanged` went with it. Nothing real was lost by not porting the delayed `restoreSavedDeviceVolume` after `stop()`: upstream `6dacef7` already restores inline at `:405-408`. So this branch is the better base — it needs the ratio ramp (finding 1) and the no-op write guard back, plus a decision on the burst, not the whole older diff.

## 4. Contract/ripple (checked paths)

- `LocalPlayer.volume`: the only caller in the tree is `LocalPlaybackService.kt:358` (`audg` → `(left + right) / 2`; `git grep -n "\.volume = "` on the branch). One predicate therefore covers every server volume — good for the design, and the reason both findings land on this single entry point.
- `updatePlayerVolume` (`:378-410`) callers: `:115` (`isSetVolume = true`), `:316`/`:329` (`false`). The `false` calls never apply a volume in `Device` mode and only re-apply `lastSetVolume` while playing in `DeviceWhilePlaying` — hence "discarded means lost".
- State fields: `lastSetVolume` written at `:114`/`:359`, read at `:103`/`:379`; `playerInternalVolume` written at `:110`/`:388`, read at `:288`/`:389`; `lastSavedDeviceVolume` at `:121`, `:360-362`, `:398-400`, `:405-408`.
- Other volume paths: the media session forwards a controller's volume to the server (`SqueezeboxMediaPlayer.kt:90-93`), advertises the volume commands (`:221-225`) and reports the *server's* 0..100 volume as the session's device volume (`:253-261`). With the local player active, a head-unit volume command travels server-side and comes back as an `audg`, so it is subject to the fade guard; only AVRCP absolute volume (the new `onDeviceVolumeChanged`) bypasses it. The app's UI volume is server-side throughout (`VolumeFragment.kt:63`, `:108`, `:90`).
- Mode pref/default: `PreferenceExtensions.kt:81-94` (`DeviceWhilePlaying` by default).

## 5. Concurrency/lifecycle

- No threading race: the `audg` handler runs in `lifecycleScope` (`LocalPlaybackService.kt:235`) on the main dispatcher and ExoPlayer delivers `onPlayWhenReadyChanged`/`onDeviceVolumeChanged` on the application's main thread, so setter and adoption are serialized. What matters is packet order.
- The window is armed by the app's *reaction* to a server packet (`player.paused = ...` at `LocalPlaybackService.kt:363/383/386/401` → `:322`), so anything the server sends before that reaction is outside both guards (§1, last paragraph).
- Adoption runs before the restore (`:325` vs `:329`), which is what neutralizes `:405-408`; ordering-dependent, so it deserves a comment if intended.
- `SystemClock.uptimeMillis()` (`:322`, `:372`) does not advance during deep sleep, so a sleeping device keeps the window open longer in wall-clock terms; `elapsedRealtime()` would match a wall-clock window. Pick one and say why. Because the window re-arms on every toggle, rapid pause/play/pause keeps it open continuously — the state in which a real change most easily lands inside it.

## 6. Style

- Comments are short and factual, no filler, but the same rule is stated four times (`:106-109`, `:323-324`, `:345-350`, `:365-369`) — and one of them carries the wrong `Device`-mode reason, so collapsing to the setter comment plus the predicate KDoc would remove that too.
- `lastPauseToggle`/`PAUSE_FADE_WINDOW_TIME` are named after the pause but cover resume and the initial prepare as well.
- Diff hygiene is clean: one file, +61/−0, no unrelated hunks, no mode changes, no trailing whitespace (`git diff --summary`; `git diff … | grep -P '^\+.*[ \t]+$' | wc -l` → 0). Added lines measured arithmetically: longest is 101 columns (`:123`), one over the 100-col `android_studio` limit, all others ≤ 98. `check-report.txt` reports ktlint clean, so its line-length rule evidently ignores that KDoc line, but it is still worth wrapping.

## 7. Tests

- The tree has exactly the two test files the brief names (`ListResponseTest.kt`, `FlacMetadataCachingDataReaderTest.kt`; `git ls-tree -r --name-only … | grep src/test`), and the unit-test classpath is JUnit 4 only (`app/build.gradle.kts:137`, `gradle/libs.versions.toml:25`) — no Robolectric, no MockK, no coroutines-test.
- `LocalPlayer` cannot be built in a JVM test: it creates an `ExoPlayer` in `init` (`:136-139`) and reads `SharedPreferences` (`:76`), and the values under test come from `player.deviceInfo`/`player.deviceVolume` (`:357-358`). A test today means inventing that harness — not the price of this fix; an `audg` log from the device is the evidence that fits.
- If a test is wanted, extract the decision as a pure function (`isServerVolumeFade(now, mode, isPlaying)`) and test the four combinations plus "a non-fade value must reach `lastSetVolume`". A few lines of refactor, no new dependency: worth offering, not worth blocking on.

## 8. What to ask the maintainer for

- One sentence on ordering: does LMS send the pause/resume ramp before or after its own pause/unpause packet? A debug log of `audg` with the player state settles the residual-risk question.
- Decide the semantics once: while paused in the device modes, is the device volume authoritative (then the server's value must not be applied *and* the app's slider must not pretend otherwise), or is the server's mixer volume authoritative (then re-apply it on resume)? The draft does neither consistently.
- Remove the stream-volume write of the fade (or make it a ratio) and never put a fade value into `playerInternalVolume`.
- Fix or delete the wrong `Device`-mode reason at `:352-355`, and decide whether `Device` should adopt as well.
- Shorten or drop the 2 s window; if it stays, justify the number and add back the "don't write an unchanged device volume" guard for Bluetooth head units.
- Say whether losing upstream's restore-while-paused in `DeviceWhilePlaying` is intended.
