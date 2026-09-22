# Review — stream start on resume (`f6fc998`, integration branch)

**Reviewed against** upstream `6dacef7`; the change is commit `f6fc998` on 
`integrate/upstream-favs-sync-fix`, no branch of its own. Read-only review; no build was run here 
(see `reviews/README.md`).

**Verdict.** Right diagnosis, right layer: upstream's reader works only inside one process, so 
keeping a file start across a process death is the right idea in the right place. But the benefit 
is narrower than the PR text claims — pairing a stream with a stored start can be wrong in both 
directions, and the in-memory replay path is not guarded at all. The FLAC port is faithful, so the 
deletion is not the problem: a FLAC-only bug is answered with ~640 new production lines plus an Ogg 
path the store rule mostly disables.

**What holds up.** Upstream's cache is empty at process start 
(`FlacMetadataCachingDataReader.kt:53-54`, deleted: `throw IOException("Trying to seek without 
metadata")`); the frame-sync scan and frame-header predicate carry over unchanged (deleted 
`:64-123` → `StreamContainer.kt:167-182`); the deleted test's two cases are the new tests 1 and 
3; and eviction is per track id with `load()` refreshing the mtime (`StreamPrefixStore.kt:45`, 
`:69`), so "most recent four" is not a real risk.

## 1. Correctness — the pairing is a guess that fails both ways

**(a) Duration is not an identity.** `LocalPlaybackService.kt:609-615` accepts a captured start 
when its STREAMINFO duration is within 2 s of the duration of the track the server reports as 
current *at query time* — but the stream being read need not be that track. Normal queue case: a 
new track's `strm-s` does not continue the current stream (`:745`), so it is *appended* 
(`LocalPlayer.kt:248-254`) and flagged `fromPreloadStream = true` (`:212`). If `|dur(N+1) − 
dur(N)| ≤ 2 s`, N+1's start is stored under **N** (`:616-619`), overwriting N's correct entry; 
otherwise it is dropped and N+1 gets no entry. Adjacent album tracks are exactly where durations 
are close, and cue-split albums never match.

**(b) The in-memory path has no guard and is consulted first.** 
`StreamPrefixCachingDataReader.kt:72` publishes every captured start into the single per-player 
`StreamStartCache` (`LocalPlayer.kt:82`) without asking whose stream it is; `:77` hands it to any 
later resumed stream, and `:128` returns it before the service is asked, so the store is not 
consulted while anything is cached. Once N+1's appended stream has been read, a mid-file restart of 
N replays N+1's header. Inherited from upstream, but the PR's "only stored for a track that is 
known" holds for the store alone.

**(c) For Ogg the guard disables its own store path.** `streamDurationSeconds` is null for Ogg 
(`StreamContainer.kt:64`, no override), so `belongsToCurrentTrack` (`:614-615`) reduces to 
`!fromPreloadStream` — and per (a) every new queue track is appended. An Ogg start is stored only 
for a stream started while the player was idle, or a restart of the current stream that happens to 
begin at the file start; after an app restart mid-track the reader throws as 
before, so ~140 lines of Ogg parsing buy almost nothing here.

**(d) Worst outcome, detectability.** The replayed header is never compared with the resumed data 
(`StreamPrefixCachingDataReader.kt:77-82`) or with the server's duration for the track being loaded 
(`LocalPlaybackService.kt:558-569`). Same-album FLAC mis-pairing is quiet: rate and channels 
usually agree, frames decode, and the damage is only a wrong duration inside ExoPlayer (≤2 s) — 
invisible, and the `Diag.log` lines (`:564`, `:620`) log neither duration. Cross-format mis-pairing 
mis-configures the decoder; whether media3 1.11.1 errors or decodes at the wrong rate was **not 
verified here** (hypothesis).

**(e) The PR text's *Gap* is backwards.** A track the player never streamed from its start does not 
"still fail as before" within a session — `StreamPrefixCachingDataReader.kt:128` returns whatever 
the cache holds; only on a fresh process does it fail.

## 2. Over/under-reach — the deletion

`git show --numstat f6fc998`: 345 removed (330 of them the deleted reader and its test), 1170 added 
(964 in the four new files). For FLAC the replacement is equivalent — the only change for a 
captured start is that trailing PADDING is dropped (`StreamContainer.kt:104-132`) — and the 
deleted class has no remaining references (`git grep FlacMetadataCach f6fc998` → none). Fine as a 
refactor; whether it is worth 640 production lines for a FLAC-only bug is the packaging question 
— and Ogg, never run against a real stream, is the part to split off.

Under-reach and a wrong size claim: "a few hundred bytes" holds only for a bare file — 
`reduceStreamStart` drops PADDING only (`StreamContainer.kt:104-132`), so a PICTURE block (embedded 
art) or a SEEKTABLE stays in the entry, in memory and on disk four times 
(`StreamPrefixStore.kt:39-59`). FLAC has no size cap, unlike Ogg's 64 KB / 8 pages 
(`StreamContainer.kt:325-328`).

## 3. Surgical alternative

The smallest change that fixes the reported failure without a cross-track guess:

1. Keep upstream's `FlacMetadataCachingDataReader`; give it one collaborator (`headerFor(trackId)` 
/ `storeHeader(trackId, bytes)`) instead of the container abstraction — one new file, ~60 lines.
2. Decide *whose* header was captured where the identity exists: at the `strm-s` command and the 
following status change (`LocalPlaybackService.kt:683-746`), not when the bytes are read — a 
continuing stream belongs to the track being played, an appended one to the track the next status 
change reports.
3. On load, compare the entry's stored duration with the server's current-track duration and log 
both; keep two candidates instead of one.
4. Ogg as a follow-up with a real reproduction.

Costs: A (download the start) adds a server round trip and depends on the download route the app 
already uses (`DownloadWorker`); B (prime at 0) costs two stream restarts and re-enters position 
bookkeeping; C (synthesize STREAMINFO) needs a frame-header parser, cannot know total samples, and 
is FLAC-only.

## 4. Contract/ripple

New surface: `StreamContainer` (`StreamContainer.kt:43`), 
`StreamPrefixCachingDataReader`/`StreamStartCache` (`StreamPrefixCachingDataReader.kt:45`, `:113`), 
`StreamPrefixStore` (`StreamPrefixStore.kt:35`), `LocalPlayer.publishStreamStart` 
(`LocalPlayer.kt:265`), two new `LocalPlayer` callbacks (`:75-76` → 
`LocalPlaybackService.kt:160-163`) and two new `LocalPlayerMediaExtractor` params 
(`LocalPlayerMediaExtractor.kt:40-42`). The store is called from `LocalPlaybackService.kt:561` and 
`:618` only. Ripple: the tag change (`CurrentTrackInfoRequest.kt:31`) and response fields 
(`CurrentTrackInfoResponse.kt:32-33`) are shared with the favorites draft's request 
(`NowPlayingFragment.kt:452`).

## 5. Concurrency/lifecycle

Writes are atomic (tmp + rename, `StreamPrefixStore.kt:47-49`), but a `.tmp` left by a kill counts 
as an entry in the sweep (`:76-79`) and can push out a real one. `await` blocks the loading thread 
at most 3 s (`StreamPrefixCachingDataReader.kt:105`, `:127-143`), and a known-failed lookup returns 
at once (`LocalPlaybackService.kt:546-548`). The service's 
`streamStartLoadActive`/`streamStartLoadFailed`/`pendingStreamStart` (`:126-132`) are touched from 
the loader thread and from main-thread coroutines with no `@Volatile`, and the single 
`pendingStreamStart` slot lets a second capture replace the first before the store job reads it 
(`:596-599`, `:603`), so that capture is never stored.

## 6. Style

Mostly short and factual. Four comments read as rationale essays 
(`StreamPrefixCachingDataReader.kt:121-126`, `LocalPlaybackService.kt:533-541`, `:582-590`, 
`StreamContainer.kt:29-41`); the reasoning belongs in the commit message. `LocalPlayer.kt:261-264` 
is wrong twice: that call fetches nothing from the server, and the path is not FLAC-only. 
`LocalPlaybackService.kt:142-143` misses the blank line before `override fun onCreate`. One added 
line is 101 columns (`StreamPrefixStore.kt:88`) — a nit, upstream already has longer lines.

## 7. Tests

The eight tests: `readingFullFlacStreamCachesMetadata`, `readingFullFlacStreamDropsPaddingBlock`, 
`resumedFlacStreamContinuesAtNextFrameSync`, `resumedStreamWithoutStoredStartFails`, 
`readingFullOggStreamCachesCodecSetupPages`, `resumedOggStreamContinuesAtNextPacketPage`, 
`resumedOggStreamSkipsPagesContinuingAPacket`, `oggStreamOfUnknownCodecKeepsItsFirstPage` 
(`StreamPrefixCachingDataReaderTest.kt:174-293`). They cover the reader against synthetic streams 
and an in-memory cache, including the padding drop and both resume cases.

Untested: the whole store; the pairing rules in `storeStreamStart`/`provideStreamStart`, the only 
part that can be wrong; the parsed duration (`StreamContainer.kt:141-164`, filler STREAMINFO); any 
metadata block but PADDING; `await`'s timeout path (the failure test uses the default 
`onStreamStartNeeded = { false }`, `StreamPrefixCachingDataReader.kt:49`); the mime/URI dispatch 
(`LocalPlayerMediaExtractor.kt:95-104`); and Ogg against a real file, which the test library lacks. 
Nothing here would catch a wrong pairing, because nothing in the design can detect one.

## 8. What to ask the maintainer

- Whose header is this? Pick an attribution a 2 s duration coincidence cannot confuse: 
status-transition attribution, a same-file identity from the server, or two candidates not one.
- Is the Ogg half wanted now, or as a follow-up with a real reproduction? Today it changes almost 
nothing (finding c).
- Is the container refactor worth it, or should this be upstream's reader plus a durable per-track 
store plus a load-time duration check? And does `/music/<id>/download` really not answer in 
general, or only on the author's LMS — it is the app's own download route.
