# Review notes — draft PRs

Critical reviews of the drafts in `../pr/`, written against upstream `6dacef7` (still the current
upstream HEAD, so no draft has drifted). Each file judges one draft: what holds up, what does not, and
the smallest correct form of the change — so the maintainer can either take the fix or reimplement it
their own way from the insight.

**No code was changed by any of these reviews.** They are notes; the drafts themselves are untouched.

## Status — all drafts reviewed

| branch / draft | review | headline |
| --- | --- | --- |
| `fix/cometd-request-while-disconnected` | [`…cometd-request-while-disconnected.md`](fix-cometd-request-while-disconnected.md) | Sound fix, right place. Narrow the `catch (e: Exception)` in the paging source; fix or reword the initial-request catch. Contract check the brief asked for is cleared. |
| `fix/local-position-display` | [`…local-position-display.md`](fix-local-position-display.md) | Diagnosis right, diff not proportionate. The holder mixes a per-player position with a **global** duration/generation fed by whichever player the session controls, and it silently changes what is sent to the server. Land last of the four position drafts. |
| `fix/mediasession-next-at-playlist-end` | [`…next-at-playlist-end.md`](fix-mediasession-next-at-playlist-end.md) | Clamp is right and kills the crash by construction. But an empty playlist window still throws (unfixed path, now on a line the PR added), and the advertisement is window-relative while the app asks in absolute terms. |
| `fix/mediasession-pending-track` | [`…pending-track.md`](fix-mediasession-pending-track.md) | Right vehicle, wrong lifetime rule. The predicted song is looked up from the applied position while the offset accumulates, so a second press inside the flap can publish index N+2 with N+1's metadata; the offset can also outlive the server applying it. |
| `fix/position-after-disconnect` | [`…position-after-disconnect.md`](fix-position-after-disconnect.md) | Right ideas, size not earned (274 lines, 10 fields, 8 constants). Sticky flags misclassify the next stream after a plain pause; the stream-URL guard is dead; the hand-off request is unprotected. |
| position a seek asked for | [`…position-after-seek.md`](fix-position-after-seek.md) | Requested-position base and generation counter are sound. The port dropped the next/prev `note()` calls, so "seek, then Next within 3 s" bases the new track's stream on the old seek position; the slot is unkeyed and survives failures. |
| `fix/session-reannounce` | [`…session-reannounce.md`](fix-session-reannounce.md) | The lever does not reach the input: media3's `addSession`/`removeSession` never release or re-activate the framework session a head unit reads, so the re-announce cannot work as described (it does drop the notification/FGS). The quoted log line comes from a different commit. |
| `fix/slider-drag` | [`…slider-drag.md`](fix-slider-drag.md) | Diagnosis, placement and size right. `userSeeking` can be left set by Material itself (slider disabled mid-drag), freezing the bar; seeks ≤3 s are not held at all; the identity test differs from the fragment's own. |
| `fix/volume-device-volume-fades` | [`…volume-device-volume-fades.md`](fix-volume-device-volume-fades.md) | Adoption idea right. But the fade branch writes the server's absolute fade into `player.volume` with nothing restoring it, and a discarded value is discarded for good — a genuine app-UI change made while paused never reaches the device volume. |
| `feature/nowplaying-favorite-toggle` | [`…favorite-toggle.md`](feature-nowplaying-favorite-toggle.md) | Capability worth having, but it lands as the toolbar chrome the maintainer already rejected. A failed write *looks like success* (the failure path cancels the caller's coroutine, so the rollback never runs), and the button is visible before the state is known. |
| stream start on resume (`f6fc998`) | [`stream-start-on-resume.md`](stream-start-on-resume.md) | Right diagnosis and layer; the FLAC port of the deleted upstream reader is faithful, so the deletion is not the problem. Pairing a stream with a stored start can key the wrong track silently; the in-memory replay path bypasses the guard; the Ogg half is largely inert. |

## Verification done here (independent of `check-report.txt`)

**Compile.** Every branch in the review set was checked out alone in a scratch worktree and built with
`:app:compileFossDebugKotlin` on a Linux toolchain — Temurin JDK 21, Android SDK platform 37.0,
build-tools 37.0.0, Gradle 9.6.1 via the repo's wrapper. **All nine compiled.** This is a second,
independent confirmation of the author's Windows run. `stream start on resume` (`f6fc998`) was *not*
compiled standalone, because it is not standalone — it builds on the current-track-info request of the
favourite-toggle draft, and the compile check would fail for that reason rather than a real one.

**Spot checks of load-bearing claims** (done by re-reading the code, not by trusting the review):

- the paging `catch` is the only `IllegalStateException` catch in `app/src/main`, and broad
  `catch (e: Exception)` appears nowhere else except `DownloadWorker` — so "not house style" holds;
- `coerceIn(min, max)` on an empty window really does throw, and empty windows are reachable
  (`PlayerStatusResponse.kt:140-142` returns `emptyList() to count`; `ListResponse.kt:25-27` documents
  the shape). Amended in that review: pre-fix the same shape already failed media3's own index check, so
  it is an unfixed path, not a regression introduced by the draft;
- the port omission in the seek draft is real: `note()` has one call site on `759b462`
  (`ConnectionHelper.kt:326`) but three on the integration branch (`:344`, `SqueezeboxMediaPlayer.kt:154`
  and `:163`);
- `LocalPlayerPosition` is an `object` whose position is keyed per player while `songDuration` and
  `songGeneration` are plain globals, written from `SqueezeboxMediaPlayer.kt:407`/`:410` — i.e. by
  whichever player the media session controls;
- the favourite failure path is exactly as described: `requestOrNull` returns null only while the
  coroutine is still active, and `ConnectionHelper` cancels the caller's own coroutine on a
  connection-level failure;
- `fix/mediasession-pending-track`: `songAtOffset()` reads the applied `playlistPosition`, while
  `updateUnacknowledgedState()` accumulates `existingOffset + playlistPositionOffset`;
- `fix/session-reannounce`: fetched media3 `1.11.1` `MediaSessionService.java` — `addSession`/`removeSession`
  only mutate the session map, the notification manager and a listener; the framework session is never
  released or re-activated;
- style claims measured: the 101-column line at `StreamPrefixStore.kt:88` is exactly 101 columns, and the
  99-column maximum in the next-at-playlist-end draft is accurate.

**Not verified here:** anything needing media3 internals beyond the file above, a device, or a car
(foreground-service promotion, head-unit behaviour, notification behaviour, actual audio). Those are
marked as hypotheses inside the individual reviews, with the device check that would settle each.
