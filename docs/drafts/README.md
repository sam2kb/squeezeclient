# Draft PRs - review kit

This directory is the tracked copy of the review kit: it ships on the integration branch
(`integrate/upstream-favs-sync-fix`), so another agent can fetch the fork and review every draft
without access to this workstation. The scratch originals stay in git-ignored `build/drafts/`.

All branches are single commits on top of upstream `maniac103/squeezeclient` as of `6dacef7`, and
they are pushed to the fork `origin` (`git@github.com:sam2kb/squeezeclient.git`) - **not** to
upstream. Nothing here is proposed upstream yet.

## The review set (pushed to `origin`, ready to review)

`diff` = what the PR would add (`git diff --shortstat 6dacef7 <branch>`); PR text = file in `pr/`.

| branch | commit | subject | diff | PR text |
| --- | --- | --- | --- | --- |
| `fix/cometd-request-while-disconnected` | `1c453ab` | CometD: Don't crash when a request is submitted while disconnected | 2 files, +15/-4 | `pr/pr-cometd-crash.md` |
| `fix/local-position-display` | `9fbb028` | Show the position the local player plays instead of the server's estimate | 4 files, +108/-10 | `pr/pr-local-position-display.md` |
| `fix/mediasession-next-at-playlist-end` | `b10065d` | Media session: Do not offer Next/Previous when there is no next/previous track | 1 file, +13/-6 | `pr/pr-mediasession-next-at-end.md` |
| `fix/mediasession-pending-track` | `4f3caf6` | Report the expected song for our own track changes | 1 file, +35/-8 | `pr/pr-pending-track-flap.md` |
| `fix/position-after-disconnect` | `759b462` | Local player: Keep the position across a server-initiated stream restart | 4 files, +251/-5 | `pr/pr-position-after-disconnect.md` |
| `fix/session-reannounce` | `a6b1af8` | MediaService: Re-announce the session when a device isn't monitoring it | 1 file, +28 | `pr/pr-session-reannounce.md` |
| `fix/slider-drag` | `3d4c247` | Now playing: Don't let status updates fight the position slider | 1 file, +95/-5 | `pr/pr-slider-drag.md` |
| `fix/volume-device-volume-fades` | `ba7f766` | Local player: Keep the device volume across playback state changes | 1 file, +61 | `pr/pr-volume-fades.md` |
| `feature/nowplaying-favorite-toggle` | `6dc0c91` | Now playing: Add a one tap favorite toggle | 14 files | PR body is on the fork (this branch had one before) |

Two more drafts have no branch of their own:

| draft | where | why |
| --- | --- | --- |
| stream start on resume (built-in player) | `integrate/upstream-favs-sync-fix`, commit `f6fc998` | uses the current-track-info request of the favorite toggle, so it does not stand alone on `6dacef7` - `pr/pr-stream-start-on-resume.md` |
| position a seek asked for | inside `fix/position-after-disconnect` (`759b462`) | written on top of `PositionChangeRequests`; ported by hand, see `pr/pr-position-after-seek.md` |

Rebase state: `feature/nowplaying-favorite-toggle` was rebased onto `6dacef7` and squashed to one
commit (`6dc0c91`) after this session started - everything else in the table above was already a
single commit on `6dacef7`. Conflict-free rebases were possible for nine of the older branches; the
rest are listed as obsolete below.

## Obsolete (not in the review set, not pushed again)

| branch | why |
| --- | --- |
| `fix/flac-resume-after-pause` | merged upstream in the meantime |
| `fix/cometd-utf8-decoding` | merged upstream in the meantime (PR #63) |
| `fix/favorites-duplicate-entries` | upstream now has the same fix (`88702f4`, same test names) |
| `fix/media-session-state-stability` | PR #61 was closed; the maintainer reimplemented it |
| `fix/media-session-lifetime` | superseded by the maintainer's media session rework (the code moved to `service/mediasession/`) |
| `fix/volume-follow-device-changes` | old, much larger attempt at what `fix/volume-device-volume-fades` does in 61 lines |

Older copies of some of these are still on the fork from before; ignore them.

## How to review one draft without this workstation

```bash
git clone git@github.com:sam2kb/squeezeclient.git && cd squeezeclient
git remote add upstream https://github.com/maniac103/squeezeclient.git
git fetch --all
git checkout <branch>                 # every branch in the review set is one commit on 6dacef7
git log --stat 6dacef7..HEAD          # what the PR would change
bash docs/drafts/check.sh <branch>    # worktree + ktlint + compile, per branch
```

`check.sh` needs the Android SDK (`local.properties` or `ANDROID_HOME`) and a JDK 17+; without
`cmd.exe` it uses `./gradlew`, and `GRADLE_CMD=...` overrides the compile command entirely.

On the author's machine the drafts were verified on a real device (Samsung foldable, Android 15,
debug build) against a Lyrion 9.1.2 server, with the built-in player; `REVIEW-BRIEF.md` lists what
was verified for each draft (and how, so it can be repeated) plus what to be suspicious about.

## Device recipe (author's setup, for repeating a verification)

```bash
adb -P 5038 -s RFCX91M4DAK install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
adb -P 5038 -s RFCX91M4DAK shell am start -n de.maniac103.squeezeclient.debug/de.maniac103.squeezeclient.ui.MainActivity
```

LMS at `http://10.10.2.45:31101/jsonrpc.js`, player id `50:85:82:13:79:5c`, app log at
`/sdcard/Android/data/de.maniac103.squeezeclient.debug/files/diag.log`
(`grep -E 'strm-s|hand-off|adopted|status=Timer|song changed'`). Useful sequences:

- **position / slider**: play a track, `input swipe` on the slider (y=1655, x 170..1685 on the
  unfolded screen), then `status` on the JSON-RPC for the server's view; the app logs
  `status=Timer position=…` once per second. Seek/seek/pause/play is the interesting sequence.
- **connection loss**: `adb shell svc data disable` (Wi-Fi alone is not enough, the phone falls back
  to mobile data), then restore.
- **media session**: `input keyevent 87/88`, or `dumpsys media_session | grep TITLE`; the log records
  `cmd: seek`, `state: applied`, `getState: published`.
- **volume**: `adb shell media volume --show --stream 3 --set 9`, then trigger a fade (pause/resume,
  stream restart) and read the device volume back.

## What to check for every PR

1. Does the change make sense for *every* user of the app, not just for this library/server (a
   FLAC-only special case was rejected for exactly this reason)?
2. Is the commit self-contained - no references to integration-only helpers or classes?
   `check.sh` catches that by compiling each branch alone.
3. Comment style and diff size in line with the repository (short factual comments, no reformatting
   of untouched code, no AI-flavoured prose).
4. Does the PR text carry problem, mechanism, evidence (log lines), reproduction and the exact
   change? The maintainer asks for measurable evidence, not adjectives.
5. Does it conflict with another draft that would land first? There are three such pairs, all with
   `fix/local-position-display` (see the report and the PR texts) - the second PR always needs a
   rebase, and saying so in the PR text is part of the story.
6. Does it need a unit test? Upstream tests the extractors under `app/src/test`; local player
   changes usually should come with one (the stream start draft has 8).

## The check script

```bash
bash docs/drafts/check.sh                    # all reviewable branches
bash docs/drafts/check.sh fix/slider-drag    # one branch
SKIP= GRADLE_CMD='./gradlew --quiet :app:compileFossDebugKotlin' bash docs/drafts/check.sh
```

For every branch it detaches the branch in a scratch worktree under
`/mnt/d/repos/squeezeclient-drafts/`, runs ktlint on `app/src`, compiles it with the Android
toolchain and then checks every pair of drafts for merge conflicts. The report is written next to
this file (`check-report.txt`), per-branch build output to `/tmp/drafts-check/<branch>.log`.
The checked-out report was generated with the full branch set (obsolete branches included, hence the
`STALE` entries at the bottom); `check.sh` skips those by default.
