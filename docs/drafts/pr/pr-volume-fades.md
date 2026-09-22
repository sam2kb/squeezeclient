# Local player: the device volume does not survive a pause toggle or a stream restart

**Problem**: in the volume modes that drive the device volume (`device`/`devicewhileplaying`, which
is what a phone connected to a car head unit uses), the volume changes by itself: pausing and
resuming lowers it step by step, and every stream restart - a network blip, a reconnect, or the
server restoring a session - resets it to the volume the server has in its own mixer.

**When exactly**:
- on every pause/resume toggle: the server ramps its volume (`audg`, about 0.3 s of steps) around
  the toggle, and those values were applied to the device volume and remembered as the desired one;
- when a stream is set up or stopped: the server announces its mixer volume, which then became the
  device volume (measured: a clean reconnect after a 32 s outage changed the volume);
- whenever the app re-applied the remembered volume, e.g. on the next track - the value it
  remembered was the last one the server had sent, not the one the user had set on the device.

**Why** (mechanism):
- In these modes the app volume is translated into the device volume, so *every* value the app
  accepts ends up as the device volume. Not all server values are user intent: the server ramps the
  volume when pausing/resuming, and it announces its own volume when a stream is started or stopped
  (including a temporary `volume 0` before a fade-in).
- Nothing distinguished those values from a volume the user set, so they were stored and later
  re-applied.
- The reverse direction had the same problem: a volume set on the device (car head unit, Bluetooth
  absolute volume) was only picked up on the app's own playback state changes, so the next
  re-application of the remembered value overwrote it.

**Evidence**: measured on device - pausing and resuming lowered the device volume by the fade
steps, and a stream restart after a network blip set it to the server's mixer volume.

**Suggested minimal change** (implement however you prefer):
- treat a volume that arrives right after a playback state toggle, or while nothing is playing, as
  a fade: ignore it entirely: following the ramp leaves playback at whatever value the ramp was
  cut off at (a dropped connection can cut it off at zero) and recording or applying it changes
  the volume the user chose;
- adopt the volume the device is currently set to when the playback state toggles, when a stream
  is set up or stopped, and when the device volume changed externally - the user's volume is what
  playback continues with.

Diff: 3 files, +139 (`service/localplayer/LocalPlayer.kt`, `service/localplayer/LocalPlayerVolume.kt`
(new), `service/mediasession/SqueezeboxMediaPlayer.kt`).
Branch: `fix/volume-device-volume-fades` (`43379a8`, off `upstream/main` `6dacef7`), pushed to the
fork (`origin`), not to upstream.

## For the reviewer

One commit (`43379a8`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). Two drafts conflict with it in
`service/mediasession/SqueezeboxMediaPlayer.kt`, one small hunk each: `fix/local-position-display`
(both add to the state that is published) and `fix/position-after-disconnect` (its seek handlers sit
next to the advertised commands); whichever lands second rebases.

It also carries what the review and the device runs after it asked for: the server's fade ramp is
ignored rather than followed (a ramp can be cut off by a reconnect and leave playback at its last
value), and the mixer volume from the player status is the authority - it is applied when it changes
and a volume clearly below it is ignored, because the server repeats the value a ramp stopped at.

An earlier and much larger attempt at the same problem is on `fix/volume-follow-device-changes`
(an old PR #57, +203/-11 in the same file): it stays in the repository for reference but is
superseded by this draft and is not part of the review set.
