# The media session keeps reporting the last song after the queue is cleared

**Problem**: when the server's playlist becomes empty (the queue is cleared, or the server stops the
player after a network interruption), the app keeps reporting the song it played last, with a frozen
position, for as long as the queue stays empty. The notification, the lock screen and any connected
device show metadata for a track that is not playing, and the position never moves again. In the
measured case the queue had been empty since 10:36 and the last state the app applied was at 10:46 -
nothing was applied for the hour that followed.

**Why** (mechanism):
- The player fetches the playlist whenever a status reports a new playlist revision (`lastChange`),
  and only continues the state update when the response *matches* the revision it was started for:
  `if (playlist.timestamp == status.playlist.lastChange) { ... schedulePlayerStateUpdate() }`.
- For an *empty* playlist the server reports a fresh revision on every request - the `status` and
  the `playlist` response carry timestamps milliseconds apart - so that condition is never true.
  Measured over one log: 109 of 109 responses for empty playlists mismatched, while all three
  responses for non-empty playlists matched.
- Dropping the response also means `schedulePlayerStateUpdate()` is never called again, so the state
  stays at whatever was applied last - and the song stays too, because `PlayerState` falls back to
  the previously known song whenever a status does not name one, which is what a status for an empty
  queue looks like.

**Evidence** (real run, titles shortened):
```
10:36:40.051  status: recv rev=...36.049280Z song=<none> idx=1/0 state=Stopped
10:36:40.051  state: waiting for the playlist fetch
10:36:40.119  playlist: fetch done rev=...40.118275Z items=0 index=0 wanted=...36.049280Z
              (no "state: applied" follows; the next status repeats this a minute later)
...
10:46:54.374  state: applied song='Track 1' idx=0 state=Stopped playlistRev=...14:26:36Z
              (this is what the session reports an hour later)
```
With the change, clearing the queue while playing does this instead:
```
11:52:12.849  status: recv rev=...52:12.848382Z song=<none> idx=1/0 state=Stopped
11:52:12.892  state: apply immediately (consistent=true)
11:52:12.892  state: applied song=<none> idx=0 state=Stopped
```

**Reproduction**:
1. Play a playlist with the built-in player.
2. Clear the queue on the server (`playlist clear` over the JSON-RPC API, or "clear playlist" in the
   web UI or another controller).
3. Watch `adb shell dumpsys media_session`: the metadata keeps the last song and the position stops
   moving, for as long as the queue stays empty.

**Suggested minimal change** (implement however you prefer):
- accept a playlist response when the status that triggered it also reports that nothing is playing
  (`playlist.items.isEmpty() && status.playlist.nowPlaying == null`), and treat that state as
  consistent, so `getState()` gets updated;
- do not fall back to the previously known song once the queue is empty.

Diff: one file, +22 / -5 (`service/mediasession/SqueezeboxMediaPlayer.kt`).
Branch: `fix/mediasession-empty-queue-state` (`fe80cde`, off `upstream/main` `51eb708`), pushed to
the fork (`origin`), not to upstream.

## For the reviewer

One commit (`fe80cde`) on top of upstream `51eb708`; it builds and lints on its own (see
`docs/drafts/check-report.txt`).

Note the interaction with `fix/mediasession-next-at-playlist-end`: that draft makes `getState()` skip
an empty playlist (`takeIf { it.items.isNotEmpty() }`), which covers the moment where a status still
names a song while the queue is already empty. It is deliberately not duplicated here - this change
only accepts the state when *both* agree that the queue is empty.
