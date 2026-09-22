# Built-in player: playback dies when the server resumes a stream inside a file

**Status**: implemented, container-driven (see *The fix* below). Verified on the device (FLAC in a
real library) and by unit tests for both containers. Option A below was implemented first and then
dropped: it needs the server's file download route, which does not answer on the LMS installation
this was tested with (see *Open point*).

**Problem**: playback occasionally dies outright with the built-in player while the server believes
it is still playing: no audio, the position frozen, and the server ends up cycling through the
queue. The reason is a decode failure on the stream the server resumes *inside* a file.

**When exactly**:
- whenever the first stream a player process sees starts at a byte offset instead of at the
  beginning of the file, e.g.
  - after the app process was restarted (app update, force stop, reboot, process kill) while the
    server kept its "playing at position X" state - the very first `strm-s` then resumes inside
    the track;
  - when a track is resumed/seeked into without ever having been streamed from its beginning in
    the current process (a seek from another controller, a client joining a running session, the
    server restoring its session);
  - generally any stream restart at a non-zero byte offset that happens before a stream from the
    file's beginning has been read by the current player instance.

**Why** (mechanism):
- LMS streams FLAC files 1:1; for a resumed player it starts the byte stream at the offset
  belonging to the resume position. Such a stream does not contain the `fLaC` header, and without
  the `STREAMINFO` block the extractor cannot decode it.
- `FlacMetadataCachingDataReader` handles that by caching the header of a stream that starts at the
  beginning of the file and re-inserting it for restarts that resume mid-file. The cache is a
  per-player object and is only filled by a full-stream start, so it is empty exactly in the cases
  above.
- The reader then throws `IOException("Trying to seek without metadata")`; ExoPlayer retries the
  source a few times (default 3 retries) and then fails the playback with `ExoPlaybackException:
  Source error`. The service reports it with `STMn` (`StreamingFailed`), on which LMS - as its log
  says - treats the stream as undecodable ("Decoder does not support file format, code 0") and
  stops and restarts the stream. That restart again resumes at a non-zero offset, so the failure
  repeats; each round can make LMS move on to the next queue entry.
- Net user-visible effect: the audio stops, the position display freezes (the player is idle while
  the server keeps reporting "playing"), and the server walks through the queue.

**Evidence** (2026-09-22 09:53, app started fresh, server resumed the current track at ~8m22s):

```
09:53:08.732 E/EventLogger: internalError [eventTime=0.12 ... loadError
09:53:08.732 E/EventLogger:   java.io.IOException: Trying to seek without metadata
09:53:08.732 E/EventLogger:       at FlacMetadataCachingDataReader.fillMetadataCache(...)
09:53:08.750 E/EventLogger: internalError (same error)        <- ExoPlayer retry 1
09:53:09.789 E/EventLogger: internalError (same error)        <- retry 2
09:53:11.842 E/EventLogger: internalError (same error)        <- retry 3
09:53:11.850 E/ExoPlayerImplInternal: ExoPlaybackException: Source error
09:53:11.850 E/ExoPlayerImplInternal:   Caused by: java.io.IOException: Trying to seek without metadata
09:53:11.865 D/SQZDiag: local: status=StreamingFailed position=8m 19.949s ...
```

the server reacted in its own log:

```
[26-09-21 21:29:18.1162] Slim::Player::Squeezebox2::statHandler (153) Error: 50:85:82:13:79:5c:
    Decoder does not support file format, code 0
```

and the player stayed stuck afterwards (position frozen, `playing=false`) while the server kept
cycling streams, which is how the same failure escalated on the evening before (2026-09-21 21:29):
after an unpause the server restarted the stream, the player reported `StreamingFailed` 10 s later,
and within two minutes the server had stopped and restarted the stream for six different tracks of
the album; playback was stopped by hand at 21:37.

**Reproduction**:
1. play a FLAC track with the built-in player, let it run some seconds, then force-stop the app
   (`adb shell am force-stop de.maniac103.squeezeclient.debug`) while the server keeps playing;
2. start the app again and watch logcat: the `strm-s` that resumes the track produces the
   `Trying to seek without metadata` errors and a `Source error` playback failure.
   (Helper scripts for headless playback control are in `build/helpers/`.)

## The fix

The player remembers the start of a stream that begins at the beginning of a file and hands it back
when the server resumes inside that file, so the extractor can set the decoder up from it. Nothing
is fetched from the server, and no stream position is changed.

Which containers this concerns is decided by the container, not by the reader: `StreamContainer`
provides what it takes to recognize the start of a file, to read and shorten it, to find where a
stream that resumes inside the file may continue, and (optionally) the duration of the file, and
`LocalPlayerMediaExtractor` picks the implementation for the stream's mime type:

| container | mime type | needs the start? |
| --- | --- | --- |
| FLAC | `audio/flac` | yes - the `STREAMINFO` block is needed to decode frames |
| Ogg (Vorbis/Opus/Speex) | `audio/ogg` | yes - the identification and setup packets are needed to set the codec up |
| MP3 | `audio/mpeg` | no - every frame describes itself, playback can start anywhere |
| ADTS AAC | `audio/mp4a-latm` | no - same as MP3 |

(the app announces exactly those four formats in its `HELO`; everything else the server transcodes
into one of them)

Details:
- the reader reports the start of every stream that begins at the file's start; the service stores
  it per track id (`files/stream-starts/<track id>`, a few hundred bytes, the four most recent
  entries; FLAC's padding block is dropped, Ogg's pages are kept up to the codec's setup packets)
- a stream that resumes inside the file is given the stored start, which the reader replays in
  front of it, followed by the stream's data starting at the next whole block/page
- a captured start is only stored for a track that is known: the server's current track matched by
  the duration the container states, or - for a container that does not state one - a stream the
  server started for the player itself rather than one the player reads early for the next track
- the server queries are retried for a moment, as CometD is not connected yet directly after a
  service start

**Gap**: a stream that resumes for a track this player never streamed from its beginning (a client
joining a running session, a fresh install, a seek into a track that was never played) still fails
as before; options B and C below would cover that, at the cost of a server round trip.

## Verification

Unit tests (`StreamPrefixCachingDataReaderTest`): the start of a stream is captured for both
containers (including the padding block being dropped, and an unknown Ogg codec being kept to its
first page), a resumed stream continues at the next block/page (skipping pages that continue a
packet), and a resumed stream without a stored start fails as it does today.

On the device (FLAC, app force-stopped while the server kept playing at 38.9 s):

```
11:11:25.421 local: strm-s uri=http://…/stream.mp3?player=… autoStart=true …
11:11:25.505 local: using the stored stream start for our stream
11:11:42.955 local: status=Timer … position=55.112452837s buffered=56702/…
```

no `Trying to seek without metadata` error, no `StreamingFailed`, no crash, position keeps running.

## Alternatives that were considered

### Option A - fetch the missing start from the server (dropped)

The reader asks for the metadata when it needs it and waits a moment for it; the service queries the
current track, downloads the beginning of the file from the server and extracts the header from it.

- Pros: no store; works for any resume, including the first one in a process.
- Cons: needs the server's file download route to work (it does not on the tested installation, see
  *Open point*), and adds a server round trip to the critical path.

### Option B - prime the stream at position 0, then hand off

When the player needs the start of a file, ask the server to stream it from the beginning, let the
reader capture the start from that stream, then ask the server to continue at the position the user
expects (the service already has that hand-off for connection losses).

- Pros: needs no container knowledge at all and also covers tracks the player never saw.
- Cons: two server-initiated stream restarts per resume, the priming audio has to be suppressed, and
  it re-enters the position bookkeeping that already caused a series of issues (position jumps,
  wrong hand-offs, server clocks drifting).

### Option C - synthesize the missing `STREAMINFO` from the first FLAC frame header

FLAC frame headers carry block size, sample rate code, channel assignment and sample size, so a
minimal `STREAMINFO` block can be built from the first frame that follows the resume offset.

- Pros: no store, no server round trip; also covers tracks the player never saw.
- Cons: needs a real FLAC frame-header parser and has to deal with frames that declare "read these
  values from `STREAMINFO`"; FLAC only, so the Ogg case stays unsolved.

## Open point

LMS's `/music/<id>/download` route does not answer on the tested installation (LMS 9.1.2 in a
TrueNAS app container): no response at all, tested from the workstation, from the phone and from
inside the container itself, for several track ids and player states, while `/music/<id>/cover`
answers instantly. The container has `lame` but no FLAC decoder binary, so the download/transcode
path likely has nothing to serve the file with. That is also why the app's own `DownloadWorker`
presumably does not work on that server; it is unrelated to this change.

## For the reviewer

There is no branch of its own. The change is the newest one on `integrate/upstream-favs-sync-fix`
(`f6fc998`); `git show f6fc998` is the full diff, and `git show f6fc998 --stat` lists the files:
`StreamContainer.kt`, `StreamPrefixCachingDataReader.kt` and `StreamPrefixStore.kt` are new, the
readers upstream ships for FLAC (`FlacMetadataCachingDataReader.kt`, which this replaces) are
removed, and `LocalPlayer.kt`, `LocalPlaybackService.kt` and `LocalPlayerMediaExtractor.kt` are
adapted.

It was not split into a branch because it does not stand alone on upstream `6dacef7`: it uses the
current-track-info request that the favourite toggle draft adds, so a cherry-pick conflicts in
`CurrentTrackInfoRequest.kt` and `CurrentTrackInfoResponse.kt` as well as in the three files above,
and porting it means re-adapting the hooks in `LocalPlayer`/`LocalPlaybackService` by hand (the same
kind of port `fix/position-after-disconnect` needed). The other drafts are all single commits on
top of `6dacef7` and can be reviewed branch by branch.

Verification: on device with a FLAC library (capture, store, and a resuming stream using the stored
start - 4 unit tests for FLAC and 4 for Ogg cover the reader itself). Ogg was unit tested only, the
test library has no Ogg files.
