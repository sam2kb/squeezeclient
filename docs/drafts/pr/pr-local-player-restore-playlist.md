# The built-in player's queue is lost when the server forgets the player

**Problem**: a player that stays disconnected from the server for more than five minutes is forgotten
by the server - and so is its playlist. When the built-in player comes back, the server treats it as
a new player: the queue is empty although the user did not clear it, and the interrupted queue cannot
be continued. Measured on 2026-09-28 with the built-in player: app force-stopped with 23 tracks in
the queue, after 5.5 minutes the `players` list did not contain the player any more and its queue was
gone; a 3 minute outage (below the server's limit) kept the queue.

**Why** (mechanism):
- The server forgets such a player itself: `Slim::Networking::Slimproto` keeps
  `my $forget_disconnected_time = 300;`, and a player that stays disconnected that long runs
  `forget_disconnected_client` → `['client','forget']` → `Slim::Player::Client::forgetClient` removes
  the client from `clientHash` together with its playlist. The app cannot prevent that, it only sees
  an empty player once it reconnects.
- Nothing on the app side remembers the queue: the built-in player learns it from status responses
  only, so once the server dropped it there is nothing left to restore from.

**What the change does** (built-in player only):
- while a queue is playing or paused, its urls are stored (one per entry, as the server reports them
  for `tags:u`) together with the playback position and whether playback was running;
- when the player connects and the server reports a playlist without entries, and the connection is
  fresh (the empty queue is not something the user asked for), the stored urls are added back -
  `playlist add` takes a single url per command - and, when playback was running, playback is started
  and the position is restored.

**Evidence** (device run 2026-09-28, built-in player, app gone for 5.5 minutes, queue of 23 tracks;
the log lines are from the build of the integration branch - the draft itself adds no logging):
```
12:32:17.110  playlist: restoring the playlist of the last session (23 items)
12:32:17.136  state: applied song=<none> idx=0 state=Stopped
12:32:18.942  state: applied song='Track 1' [Album] idx=0 state=Playing
12:32:19.817  local: strm-s uri=http://192.168.1.10:9000/stream.mp3?player=00:11:22:33:44:55 ...
```
Server side after the same run: `playlist_tracks: 23`, `mode: play` - the queue is back and playing.
The stored urls were checked in the app's preferences (`last_playlist_urls`, 23 entries) before the
restore ran.

**Reproduction**:
1. Play a queue with the built-in player and let the app report its state.
2. Force stop the app (or keep it off the network) for more than 5 minutes.
3. Start it again: with the change the queue comes back and playback resumes, without it the player
   is empty.

**Deliberately not done for remote players**: a player controlled from elsewhere may have had its
queue cleared on purpose (`playlist clear` from the web UI, another controller, a random mix that is
over), and the app is not the authority for it. The restore only runs for
`appContext.prefs.localPlayerId`, and only during the first two minutes after connecting, so a queue
the user clears while connected is never put back.

Diff: 7 files, +227 (`cometd/request/AddPlaylistItemRequest.kt`,
`cometd/request/PlaylistUrlsRequest.kt`, `cometd/response/PlaylistUrlsResponse.kt`,
`cometd/ConnectionHelper.kt`, `extfuncs/PreferenceExtensions.kt`,
`service/mediasession/SqueezeboxMediaPlayer.kt`, `ui/nowplaying/NowPlayingFragment.kt`).
Branch: `feature/local-player-restore-playlist` (`89a9902`, off `upstream/main` `6dacef7`), pushed to
the fork (`origin`), not to upstream.

## For the reviewer

One commit (`89a9902`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`).

- The draft carries `SharedPreferences.localPlayerMac` / `localPlayerId` itself so it stands alone on
  `6dacef7` (the integration branch has them from `e45016e`); a reviewer who merges both only needs
  one copy of the two extensions.

- `ConnectionHelper.restorePlaylist` catches per entry and rethrows cancellation, because the send
  helper of `fix/cometd-request-while-disconnected` (`publishCommand`) is not part of this branch.
  Once that draft is in, the body reduces to `publishCommand(AddPlaylistItemRequest(playerId, url))`.
- The request classes follow the existing ones (`NonPagedPlayerRequest`); `PlaylistUrlsRequest` uses
  the paging request machinery with `PagingParams.All` so the whole queue is read in one go.
- Known limitation: the restored queue starts at its first entry, and the remembered position is the
  position inside the track that was playing when the queue was remembered - so playback continues
  within the queue rather than at the exact track. Restoring the queue's current index would fix
  that and is a candidate for a follow-up (it needs one more stored value and one more command).
- The `now playing` screen clears the stored copy when the user clears the queue through its own menu,
  so an intentional clear is not undone by the next connection.
