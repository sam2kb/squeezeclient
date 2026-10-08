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
  for `tags:u`) together with the entry playback is at, the position inside that entry and whether
  playback was running; the location is refreshed for every status and periodically (every 15 s),
  because the server reports its position only every ~15 s (measured: 4 statuses per minute);
- when the player connects and the server reports a playlist without entries, and the connection is
  fresh (the empty queue is not something the user asked for), the stored urls are added back -
  `playlist add` takes a single url per command - the queue is moved back to the stored entry
  (`playlist index <n>` without starting it), and, when playback was running, playback is started
  and the position is restored.

**Evidence** (device run 2026-09-28, built-in player, 23 track queue, playback moved to entry 15 and
the queue cleared on the server while the app was force-stopped; the log lines are from the build of
the integration branch - the draft itself adds no logging):
```
13:03:14.180  playlist: restoring the playlist of the last session (23 items, index 15, position 16s, playing=true)
```
Server side after the same run: `playlist_cur_index: 15`, `playlist_tracks: 23`, `mode: play`; 34 s
later the app's own playback position was ~14 s, i.e. it was playing at the stored position. The
stored location (index 15, position 16) was read back from the app's preferences before the restore
ran.

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

Diff: 7 files, +322 (`cometd/request/AddPlaylistItemRequest.kt`,
`cometd/request/PlaylistUrlsRequest.kt`, `cometd/response/PlaylistUrlsResponse.kt`,
`cometd/ConnectionHelper.kt`, `extfuncs/PreferenceExtensions.kt`,
`service/mediasession/SqueezeboxMediaPlayer.kt`, `ui/nowplaying/NowPlayingFragment.kt`).
Branch: `feature/local-player-restore-playlist` (`0e228e8`, off `upstream/main` `51eb708`), pushed to
the fork (`origin`), not to upstream.

## For the reviewer

One commit (`0e228e8`) on top of upstream `51eb708`; it builds and lints on its own (see
`docs/drafts/check-report.txt`).

- The draft carries `SharedPreferences.localPlayerMac` / `localPlayerId` itself so it stands alone on
  `51eb708` (the integration branch has them from `e45016e`); a reviewer who merges both only needs
  one copy of the two extensions.

- `ConnectionHelper.restorePlaylist` catches per entry and rethrows cancellation, because the send
  helper of `fix/cometd-request-while-disconnected` (`publishCommand`) is not part of this branch.
  Once that draft is in, the body reduces to `publishCommand(AddPlaylistItemRequest(playerId, url))`.
- The request classes follow the existing ones (`NonPagedPlayerRequest`); `PlaylistUrlsRequest` uses
  the paging request machinery with `PagingParams.All` so the whole queue is read in one go.
- The restored queue continues at the entry and position it stopped at. The remembered position is
  the one the server reported plus the time since it arrived, so it can be a few seconds behind the
  moment the app actually stopped; the entry is exact.
- The `now playing` screen clears the stored copy when the user clears the queue through its own menu,
  so an intentional clear is not undone by the next connection.
