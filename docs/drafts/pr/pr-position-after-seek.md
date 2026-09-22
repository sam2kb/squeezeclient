# Built-in player: the position is wrong after seeking (and after pausing/playing)

**Status**: fixed in `486b53a` on `integrate/upstream-favs-sync-fix` and ported to the
`fix/position-after-disconnect` draft (`759b462`); verified on the device.

**Problem**: with the built-in player the position the app works with - and everything derived from
it: the progress bar, the media session position, the positions reported to the server - is wrong
after a seek, and the error gets worse with every further seek/pause/play. Typical report: "seek,
seek, pause, play = incorrect slider position".

**Why** (mechanism, all measured):

1. A seek is a request to the server (`SetPlaybackPositionRequest`, `["time",N]`); the server answers
   it by starting a new stream at the position it chose. The app treated that stream like one the
   server started on its own: it reset its position to zero, and after two seconds adopted the
   server's position to recover it.
2. The server's position is not a good source for that: it counts ahead while streaming (it assumes
   everything it sent was consumed), and it keeps its accounting across the stream restarts a seek
   causes. Measured: our position 53 s while the server reported 96 s; in another run the server
   reported the *end of the track* (its `time` pinned at the duration) while the audio was at
   2m21s.
3. The app has a hand-off that corrects exactly this (it asks the server to continue at our
   position), but it only ran for streams it considered a continuation, and a position request right
   before made it skip that: so after "seek, seek, pause, play" the unpause stream started at the
   server's wrong position - in the worst case at the end of the track, from where there is nothing
   to play. The playback then starved and its position froze while the app kept reporting
   `playing=true`.
4. The adoption (1) also had a guard meant to prevent it from applying to a *newer* stream; it
   compared the stream URL, which is the same for every track the server streams, so the guard never
   fired and an adoption scheduled before a seek could overwrite the position of the stream the seek
   had started.

**Evidence** (device logs, all 2026-09-22):

```
12:16:24.242 local: strm-s uri=…/stream.mp3?player=… autoStart=true position=399ms continues=false requested=68
12:16:25.312 local: status=Timer position=1m 8.926116430s        <- the requested position + playback
12:16:25.555 local: adopted server position: server=137 played=1.31s was=0s base=2m 15.69s
                                                                 <- stale adoption of the previous stream
12:38:34.288 local: strm-s uri=… position=4m 21.636649712s continues=true
12:38:34.317 local: hand-off check: server=262 ours=4m 21.636649712s difference=1
                                                                 <- no pointless hand-off, track ends correctly
```

## The fix

1. `PositionChangeRequests` remembers the position a request asked for (`note(positionSeconds)`;
   `ConnectionHelper.updatePlaybackPosition` passes it, next/prev keep passing nothing). The service
   uses it as the base of the stream that answers the request - that position is where the audio
   plays - and does not adopt the server's position for that stream.
2. The delayed adoption compares a stream counter (`streamGeneration`, incremented for every stream
   the server starts) instead of the stream URL, so it cannot apply to a stream that replaced the
   one it was scheduled for. The hand-off's retry loop uses the same check.
3. The hand-off now runs for every stream that continues an interrupted one or resumes after a
   pause, even when a position request happened just before: for those streams the server resumes at
   a position it deduced itself, which can be wrong exactly because of an earlier seek. It still
   compares positions first and does nothing when they already match, so no extra stream restart
   happens in the normal case.

## Verification

Measured on the device (progress bar fraction read from the screen, three slider seeks, then pause
and play):

| step | bar shows |
| --- | --- |
| start | 0.182 |
| seek to ~60 % | 0.607 |
| seek to ~20 % | 0.207 |
| pause | 0.216 (frozen) |
| play | 0.228 (continues from there) |

Before the fix the same sequence left the bar at the old position clamped to the new duration (i.e.
at the end), and after a pause the stream could start at the server's wrong position and stall:
position frozen while `playing=true`, no further audio.

The track now also ends at its real end and the player moves on to the next track (hand-off check
reports `difference=1` there, i.e. the server and we agree).

## What is *not* done (deliberate)

The server's notion of the position can still sit at the end of a track while the audio is minutes
away from it (measured: server pinned at the duration, our position 2m21s). Correcting it ahead of
time would need another position request, i.e. another stream restart, on every seek - and it is not
needed: playback reaches the real end of the track and the player reports it, and the app's own
position (slider, media session) no longer depends on the server's. The hand-off corrects the server
whenever a stream is restarted anyway.

## Ported to `fix/position-after-disconnect`

The draft carries the same code shape (`PositionChangeRequests`, `checkServerPositionAfterRestart`,
`handOffPositionToServer`), so a cherry-pick conflicts; the three changes are applied by hand in
`759b462` (the branch still compiles and lints cleanly).
