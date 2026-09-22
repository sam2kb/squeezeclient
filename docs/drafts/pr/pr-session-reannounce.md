# Media session: a car head unit only picks the session up after toggling Bluetooth

**Problem**: after connecting to a car head unit (AVRCP), the head unit shows no title/artist and
its buttons do nothing until Bluetooth is toggled off and on again (or the phone is re-paired).
Playback runs the whole time and the session exists - the stack just isn't monitoring it.

**When exactly**:
- when the phone connects to the head unit *while a session already exists* - music is already
  playing, or the session was created at app start - the head unit gets nothing;
- in the other direction, when the session is replaced while the head unit stays connected (service
  restarted after an update or a crash, app reconnecting): the head unit keeps addressing the old
  session, so its buttons stop working;
- it does *not* happen when the session is created after the phone connected to the head unit.

**Why** (mechanism):
- Media buttons and AVRCP are routed to the "active" media session(s) that the platform's
  `MediaSessionManager` monitors. The Bluetooth stack attaches a controller
  (`com.android.bluetooth`) to a session when it evaluates its active sessions - which it does on
  Bluetooth connect/disconnect events, not when a session appears, changes or is replaced later.
- A session that is created while the stack is not tracking it is therefore never registered, and
  nothing in the session itself triggers the re-evaluation. Toggling Bluetooth is simply the
  connect/disconnect event that makes the stack look again.
- Our logs show the controller only appearing after the manual toggle:
```
session: controller connected: com.android.bluetooth   <- only after toggling Bluetooth
```
- Playback, the notification and the session are unaffected by this; only the head unit's view of
  the session is stale, which is why it looks like "the app is not connected".

**Suggested minimal change**: when a media button arrives from a controller that is not currently
monitoring the session, re-announce the session (`removeSession(session)` followed by
`addSession(session)`). Throttle it (30 s) and only do it while playing, so it cannot disturb a
connected controller:
```kotlin
override fun onMediaButtonEvent(session, controller, intent): Boolean {
    val monitored = session.connectedControllers.any { it.packageName != packageName }
    if (controller.packageName != packageName && !monitored && announcementDue) {
        removeSession(session)
        addSession(session)
    }
    return false
}
```
`removeSession` only detaches the session; it does not stop the service or playback. This works
around the stack's behaviour rather than fixing it, but it does what a user does in that situation
anyway, without the manual toggle.

**How to check**: `adb shell dumpsys media_session | grep -A4 de.maniac103.squeezeclient` lists the
connected controllers, and `adb logcat -s MediaSessionService Avrcp` shows the stack attaching.

**Reproduction**:
1. Start playing with the built-in player, then connect the phone to a head unit (or press play
   right after connecting).
2. The head unit shows nothing / "unknown" and its buttons are ignored.
3. Toggle Bluetooth on the phone: metadata and buttons start working.

Note: this was observed with the previous media session implementation (before the player was moved
into `service/mediasession/`), so it is worth re-checking whether the current code still needs it.

Diff: one file, +28 (`service/mediasession/MediaService.kt`).
Branch: `fix/session-reannounce` (`a6b1af8`, off `upstream/main` `6dacef7`), pushed to the fork
(`origin`), not to upstream.

## For the reviewer

One commit (`a6b1af8`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). It does not conflict with any other draft - but see the note above
about it being written against the older media session code.
