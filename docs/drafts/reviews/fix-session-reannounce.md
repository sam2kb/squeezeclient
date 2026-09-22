# Review — `fix/session-reannounce` (`a6b1af8`)

**Reviewed against** upstream `6dacef7`, branch tip `a6b1af8`, diff 1 file +28. Read-only review; no build was run here (see `reviews/README.md`).

I read both sides of this change: media3 1.11.1 (`gradle/libs.versions.toml:15`) `libraries/session` and AOSP 14 `services/core/java/com/android/server/media`
sources, fetched with `curl` into `/tmp/m3` (`androidx/media` 1.11.1, `aosp-mirror/platform_frameworks_base` android-14.0.0_r1). **[verified]** = those sources or the branch.

**Verdict.** The report is credible, but the fix pulls the wrong lever: `removeSession`/`addSession` are service-local bookkeeping and never
re-register or re-activate the framework session a Bluetooth stack reads, so the "re-announcement" cannot reach the input a head unit consults.
What it does reach — the notification and foreground service — it cancels and rebuilds, and its guard cannot tell an attached head unit from an
absent one. Not mergeable as is; the transferable part is the observation plus diagnostics.

**What holds up.** The trigger is reachable in principle: a media button carries the dispatching package (`com.android.bluetooth`, media3
`MediaSessionLegacyStub.java:534-546`), and the guard at `MediaService.kt:190-191` is meaningful because `connectedControllers` does list platform
controllers (`MediaSessionImpl.java:400-405`). Threading holds (§5); the diff is clean (§6).

## 1. Correctness — it cannot fix the stated problem on any path

**The mechanism chain does not exist. [verified]** `MediaSessionService.removeSession()`/`addSession()` (`MediaSessionService.java:353-398`)
touch only a session map, the notification manager and a listener; the framework `android.media.session.MediaSession` is untouched. media3
activates it exactly once — `sessionCompat.setActive(true)` in `MediaSessionLegacyStub.start()` (`:446`) via `MediaSessionImpl.setPlayerInternal()`
(`MediaSessionImpl.java:305-308`) — and never deactivates it. AOSP 14 derives "active session" from that flag alone
(`MediaSessionRecord.java:427-429`), filters the listener list only on it (`MediaSessionStack.java:379-410`, `MediaSessionService.java:304-320`) and
pushes it only on session creation and active-flag changes (`:281, 688`) — none of which this code causes, so a stack reading
`MediaSessionManager.getActiveSessions()` sees identical input before and after. Anything else the device keys off would have to be demonstrated
(**[hypothesis]**).

**What could explain the field report. [hypothesis, AOSP Bluetooth `main`]** AVRCP's `MediaPlayerList` enumerates sessions when the profile is
constructed but pushes the available-players list to the car only in PTS mode, and a Bluetooth toggle tears the TG down and rebuilds it, so the car
re-queries. That fits "nothing until the toggle" better than an unregistered session — and no app-level session call affects it. No primary bug report
states the toggle condition; §7's Bluetooth log should confirm or kill this.

**"Written against the older implementation" is not the issue.** `f94ebb8` was a mechanical split (527 deletions / 265 + 289 additions, no
behaviour change), and the pre-move file already called `addSession(mediaSession)` in `onCreate` (pre-move `service/MediaService.kt:156`; today
`MediaService.kt:140`). What changed since sits in the state-reporting commits (`ae692ec`, `32bce60`, `caf5e3c`) — see §7.

**The trigger contradicts the symptom, and the evidence is not from this commit.** The PR says the head unit's buttons do nothing, yet the code
only runs when a key reaches *this* session (`MediaService.kt:185-201`). The quoted `device state is stale, re-announcing session` line does not
exist on this branch (`git grep -n "re-announce\|stale" origin/fix/session-reannounce -- app/src` → one comment at `MediaService.kt:281`); it is
from `d73692c`, a later implementation with extra conditions and `Diag.log`. A `controller connected` log proves even less: the legacy stub
registers a controller only when it sends a task (`MediaSessionLegacyStub.java:1061`, its only `addController` site), and a media button does not
register its sender (`:534-546`).

**The guard cannot tell "attached" from "absent".** Platform controllers are dropped after five minutes without a command
(`DEFAULT_CONNECTION_TIMEOUT_MS = 300_000`, `MediaSessionLegacyStub.java:131, 1014`), so a healthy head unit that has been quiet for five
minutes looks exactly like an absent one: the next key press, in a working car, triggers the notification/FGS cycle. The `player.isPlaying` gate
(`MediaService.kt:193`) suppresses the workaround precisely while paused or unpowered — when a dead head unit is most likely to be noticed
(`SqueezeboxMediaPlayer.kt:229-239`) — and after a service restart the new instance builds a new `MediaSession` (`:134`) once the old one was
released (`:174`), so no re-add can repair a controller holding the released token.

**`removeSession` is unguarded.** It asserts the session is added (`MediaSessionService.java:384-386`), and media3 can remove one it manages
(`MediaNotificationManager.java:144-150`, `:508-511`); the next media key would then throw `IllegalArgumentException` on the main thread. If kept,
guard with `isSessionAdded(mediaSession)` (`MediaSessionService.java:416`).

## 2. Over/under-reach

- The KDoc states an unverified platform behaviour as fact (`MediaService.kt:180-184`) while the PR text allows it was observed against the
  previous implementation; upstream hedges where it guesses (`MediaService.kt:265-266`, "arbitrarily chosen timeout").
- "It does not stop the service or playback" is half true: playback is untouched, the notification and foreground-service state are not (§4).
- The 30 s constant (`MediaService.kt:282`) is not derived from an observed interval; while the bug persists, the first key of every window tears
  the notification down again. Nor does `!= packageName` identify a device: on the media-button service path media3 synthesises the caller
  `androidx.media3.session.MediaSessionService` (`MediaSessionService.java:612-617`), which passes the test.

## 3. Surgical alternative

No smaller change makes these two calls do this job, because re-announcing is not what they do. If §1 is accepted, stop here: the mergeable
artifact is the diagnostic, not the workaround — the facts the report rests on fit in two lines:

```kotlin
val monitored = session.connectedControllers.any { it.packageName != packageName }
Log.d(TAG, "key from=${controller.packageName} monitored=$monitored playing=${player.isPlaying}")
```

If a workaround is still wanted for a device *proven* to need it, the smallest defensible form keeps that guard and adds `isSessionAdded(session)`
before `removeSession` — it still costs a notification/FGS cycle per trigger, so not without §7's evidence.

## 4. Contract/ripple — what a connected controller observes

For an attached controller (e.g. `com.android.bluetooth`) during `MediaService.kt:197-198`:

1. It stays connected: the framework session is neither released nor deactivated — no `onDisconnected`, same token, playback continues.
2. It loses the notification: `removeSession` releases the internal notification `MediaController` (`MediaNotificationManager.java:155-160`), whose
   `onDisconnected` (`:508-515`) triggers an update taking the `!isSessionAdded` branch (`:193-198`) → `removeNotification()` →
   `Util.stopForeground(service, removeNotification = true)` (`:364-371`).
3. It gets the notification back only once the replacement controller connects (`:124-153`); in between there is no notification and no foreground
   service, so SystemUI, Android Auto and any car integration reading the notification show "nothing playing", and re-entering the foreground from
   the background can fail — media3 catches `ForegroundServiceStartNotAllowedException` for exactly this (`MediaSessionService.java:922-927`).
4. Two sessions never exist (same instance and ID, `:353-379`), and the cycle does not stop the service (media3 stops it only in `onTaskRemoved`,
   `:753-758`; the app's 15-minute `stopSelf`, `MediaService.kt:267-272`, is unaffected).

## 5. Concurrency/lifecycle

- Threading is fine: the callback runs on the player's application looper (`MediaSessionImpl.java:221-222`, asserted at `:1623`), i.e. main
  (`SqueezeboxMediaPlayer.kt:58`), so `player.isPlaying` and `connectedControllers` (`:400-405` asserts the app thread) are legal reads.
- The pair is synchronous *because* of that: both calls run their notification work inline (`Util.postOrRun`, `Util.java:866-876`), so the old
  notification controller is released — and its `onDisconnected` invoked inline (`MediaController.java:739-745`, `:2237-2240`) — before
  `addSession` runs, leaving `isSessionAdded` false at `:509`, so media3 does not undo the re-add. That is ordering luck: the same handler is
  written to remove a session it still considers added.
- `lastSessionAnnouncement` (`MediaService.kt:77`) is never reset when a controller connects or disappears; no race with `onDestroy`
  (`:171-176`), since a released session gets no callbacks.

## 6. Style

- Every added line is ≤ 99 columns (`git diff 6dacef7 origin/fix/session-reannounce | grep -E "^\+" | grep -v "^+++" | sed 's/^+//' |
  awk '{print length($0)}'` → max 99), inside the 100-col limit this tree is written to; `check-report.txt:29` records ktlint clean.
- `import android.os.SystemClock` plus a bare `30000L` add a second time idiom to a file that otherwise uses `kotlin.time` (`MediaService.kt:75, 261,
  267-268`); `Clock.System.now()` with `30.seconds` would match (`elapsedRealtime` is better for a throttle, so this is consistency, not a defect).
- `SESSION_ANNOUNCEMENT_DELAY` names a minimum interval, which the comment at `:281` says correctly. Otherwise the diff is what upstream expects:
  one commit, one file, short factual comments, no leftover logging.

## 7. Tests

A test is not feasible, and the author is right: the tree has two JVM tests (`ListResponseTest.kt`, `FlacMetadataCachingDataReaderTest.kt`) with plain
JUnit 4 only (`gradle/libs.versions.toml:25,36`, `app/build.gradle.kts:137`), no `androidTest` source set and no Robolectric. Ask for instead:

- logcat with `onMediaButtonEvent` firing while `connectedControllers` holds no external controller, on `6dacef7` — that the trigger is real;
- `adb shell dumpsys media_session` while broken: the session should already be in the active list from construction, which decides whether the
  described "stale" state exists at all;
- a Bluetooth/AVRCP log (`Avrcp`, `MediaPlayerList`) showing a controller attaching after a re-announce **without** a manual toggle, plus the head
  unit updating — the `controller connected` line alone is also produced by any command the stack sends on its own;
- the same on `6dacef7`, whose state reporting keeps a buffering timeline visible to the system (`SqueezeboxMediaPlayer.kt:310-327`) and reports
  BUFFERING across a disconnect (`:229-239`).

## For the maintainer — the short form

- Don't take the workaround: the calls it is built on cannot re-announce anything the car reads, and each trigger costs a notification +
  foreground-service cycle.
- Ask the author for the four items above first; the "car logs" line quoted in the brief is not from this commit and does not verify it.
- If the report survives that, the fix is probably outside `onMediaButtonEvent`; if a guard is merged regardless, `isSessionAdded(mediaSession)`
  before `removeSession` is the one safe line.
