# Pressing Next on the last track of the queue kills the app

**Problem**: a media Next while the last track of the played queue is playing (head unit button,
media key, or the button on the now playing screen) makes the app crash with
`java.lang.IllegalArgumentException: currentMediaItemIndex must be less than playlist.size()`.
Previous on the first track is the mirror image of it.

**When exactly**: whenever the media session handles a Next/Previous and the resulting index leaves
the played list. It does not depend on the server: media3 builds the player state while it handles
the command, so the crash happens before any request is sent.

**Why** (mechanism):
- To show the result of a button press before the server confirms it, the player keeps an
  `UnacknowledgedPlayerStateChange` and adds its playlist offset to the current position when
  building the state.
- `getState()` used that sum unbounded as `currentMediaItemIndex`. On the last item the sum is
  `playlist.size`, and media3's `SimpleBasePlayer.State` requires the index to be *inside* the
  playlist - `Builder.build()` throws, inside the command handling
  (`MediaSessionStub` -> `seekToNext` -> `updateStateForPendingOperation` -> `getState()`), so the
  process dies.
- The next/previous commands are advertised unconditionally, so controllers offer buttons that
  cannot work at the end of the list.

**Evidence** (device, crash log; identifiers removed):
```
FATAL EXCEPTION: main
java.lang.IllegalArgumentException: currentMediaItemIndex must be less than playlist.size()
    at androidx.media3.common.SimpleBasePlayer$State.<init>(SimpleBasePlayer.java:1029)
    at androidx.media3.common.SimpleBasePlayer$State$Builder.build(SimpleBasePlayer.java:844)
    at SqueezeboxMediaPlayer.getState(SqueezeboxMediaPlayer.kt:311)
    at androidx.media3.common.SimpleBasePlayer.updateStateForPendingOperation(SimpleBasePlayer.java:3910)
    at androidx.media3.common.SimpleBasePlayer.seekToNext(SimpleBasePlayer.java:2576)
    at androidx.media3.session.MediaSessionImpl.applyMediaButtonKeyEvent(MediaSessionImpl.java:1764)
```

**Reproduction**:
1. Queue a few tracks and let the last one play (or press Next until the last one is playing).
2. Press Next once more - `adb shell cmd media_session dispatch next` does it without a head unit.
3. The app dies instead of ignoring the press.

**Suggested minimal change** (implement however you prefer):
- clamp the reported playlist position to the list; the pending state may still remember the
  offset, but the state handed to media3 must stay inside the list,
- only advertise `COMMAND_SEEK_TO_NEXT*`/`COMMAND_SEEK_TO_PREVIOUS*` when there is such an item,
  so controllers do not offer buttons that cannot work.

Diff: one file, +13 / -6 (`service/mediasession/SqueezeboxMediaPlayer.kt`).
Branch: `fix/mediasession-next-at-playlist-end` (`b10065d`, off `upstream/main` `6dacef7`), pushed
to the fork (`origin`), not to upstream.

## For the reviewer

One commit (`b10065d`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). It does not conflict with any other draft.
