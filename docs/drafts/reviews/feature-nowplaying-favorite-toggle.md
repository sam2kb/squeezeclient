# Review — `feature/nowplaying-favorite-toggle` (`6dc0c91`)

**Reviewed against** upstream `6dacef7`, branch tip `6dc0c91`, diff 14 files +427/−13. Read-only review; no build was run here (see
`reviews/README.md`).

Line numbers are from the branch (`git show origin/feature/nowplaying-favorite-toggle:<path>`); `git merge-base` confirms one commit on
`6dacef7`.

## 1. Verdict

Worth having — the app can add a favourite through the server's `jivefavorites` action but cannot show whether the playing track is
one (`FavoritesAddRequest.kt:22-25`) — but as written it adds the screen chrome this maintainer has already rejected and, on the
failure it will actually hit, fails silently while showing success (`NowPlayingFragment.kt:402-410`, `:460-487`). Fix that first;
placement is his to answer.

## 2. Correctness

**2.1 A failed write looks like a successful one.** `requestOrNull` (`NowPlayingFragment.kt:402-410`) returns null only when the block
throws while the coroutine is still active. A connection-level failure does not throw normally:
`ConnectionHelper.publishOneShotRequest` catches `CometdException` and cancels the caller's own coroutine
(`ConnectionHelper.kt:421-426`), so `isActive` is false, the exception is rethrown and the coroutine dies — neither the rollback
(`:481-484`) nor `favorite_error` (`:485`, `strings.xml:34`) runs, and the optimistic value from `:468` stays on screen. Tap + Wi-Fi
drop + response timeout (`ConnectionHelper.kt:450-452`) therefore yields a filled heart, no message, nothing rolled back. That error
branch is reachable only when the app was never connected (`client == null` → plain `IllegalStateException`,
`ConnectionHelper.kt:396-397`), so it is silent exactly when it matters; the `?: return@launch` guards at `:422`/`:425` are unreachable
for the same reason. Fix by discriminating on cause (`e.cause is CometdClient.CometdException`), or by returning a typed failure from
the helper instead of cancelling its caller, still rethrowing real cancellations.

**2.2 Visible while unknown, and taps then vanish.** Visibility is `expanded && currentSong != null` (`:443`) while the icon follows
`currentSongIsFavorite == true` (`:440`, `:444-457`), so an unknown state renders the outline heart and the "Add to favorites" label,
and `updateFavoriteState` resets to null then makes two sequential requests (`:413-425`): every track change of a favourite shows an
empty heart and `toggleFavorite` silently returns (`:461-463`). That contradicts the commit message ("stays hidden while the state is
still being fetched"), and after a failed fetch it never ends — nothing retries. Gate on `currentSongIsFavorite != null`, or re-fetch
on click.

**2.3 What holds up.** The stale-result guard is right: reset first, apply only if the song is unchanged (`:413-417`, `:428`), so a
late fetch cannot repaint the new track. Refreshing after a context-menu action (`:387-391`) covers the server's own `jivefavorites`
action. Collapsed/expanded and RTL handling are correct (`:441-443`, `:234-238`, `fragment_nowplaying.xml:63-64`). `MainActivity` is
the only listener implementer (`MainActivity.kt:90`, `:324-326`). The popup reuses the app's only transient surface (no
`Toast`/`Snackbar` in `app/src/main`).

**2.4 Hypotheses.** *H1 identity:* the guard compares `Playlist.PlaylistItem`s, a data class with no URL (`Playlist.kt:33-40`, same
test as `:277`), so two entries with identical tags but different files compare equal — no refresh, and a tap toggles the first one's
URL; narrow, and an id/URL in the status model is the durable fix. *H2 post-write re-read:* `:489-491` reads `favorites exists` a round
trip after our own write and drives icon (`:492-495`) and popup (`:497-506`) from it, so an add the server has not indexed yet flashes
back as "Removed from favorites"; dropping it costs only latency. *H3:* `:497` sits outside the track guard, so a track change
mid-write pops up the old title. *H4 response key:* `CurrentTrackInfoResponse.kt:25` expects `playlist_loop`, every other response uses
`item_loop` (`PlayerStatusResponse.kt:49`, `SlimBrowseListResponse.kt:45`, `JiveHomeItemListResponse.kt:36`) — author-verified live,
but a wrong key decodes to nothing and looks identical to "not known yet"; likewise `exists` defaults to false
(`FavoritesExistsResponse.kt:24-26`), reading unknown as "not a favourite".

## 3. Over/under-reach

Eight new files, six modified. Three safe reductions: `FavoritesAddRequest.kt:26-27` and `FavoritesDeleteRequest.kt:26-27` differ by
one word (54 lines, two headers) where house style keeps such a family in one file (`PlaybackButtonRequest.kt:22-28`,
`ChangePlaybackStateRequest.kt:23-40`) → −1 file; the confirmations cost `DisplayStatusFragment.kt:71-105`,
`MainActivity.kt:119-122`/`:324-326` and 3 of 5 strings (`strings.xml:32-34`) for feedback the icon already gives → 12 files if dropped
(§2.1 first); `ic_favorite_24dp.xml:26` duplicates the heart already in `hm_favorites.xml:27` (`JiveHomeItemListAdapter.kt:100`). Not
droppable: `CurrentTrackInfoRequest`/`Response` (+64 lines) exist because the URL is absent from the status payload
(`PlayerStatusResponse.kt:90-97` has no `url`; `PlayerStatusRequest.kt:23-29` sets no `tags`), and adding `u` there would also hit the
long-poll subscription built on that class (`ConnectionHelper.kt:553-566`) — the URL on every tick. One request per track change is the
cheaper trade, weighed in `CurrentTrackInfoRequest.kt:24-25`, and is the primitive the stream-start draft builds on. Unstated
limitation: a change made from another controller is not reflected until the track changes.

**Where it lands.** The transport row already carries five controls (`repeat` `fragment_nowplaying.xml:227`, `prev` `:235`,
`play_pause_wrapper`/`play_pause` `:243`/`:249`, `next` `:260`, `shuffle` `:268`); the button joins none. It is a 48dp `ImageView`
(`id/favorite`, `:60`) in a **new wrapper `FrameLayout`** (`:44-73`) overlaying `MaterialToolbar`, leading edge beside the chevron,
opposite the trailing `volume`/`info` items (`now_playing_menu.xml:21-32`, inflated at `NowPlayingFragment.kt:339`). An overlay
reserves no space, so a long localized title can collide with it: toolbar chrome, using neither extension point.

## 4. Surgical alternative

1. Fix §2.1, and gate the icon on `currentSongIsFavorite != null` (or grey it out) instead of drawing an unknown state.
2. Put it where he said: a sixth 48dp `ImageView` beside `repeat`/`shuffle` (`fragment_nowplaying.xml:226-273`), clicked like
`binding.shuffle` (`NowPlayingFragment.kt:265-268`, helper `:526-530`) — no wrapper, no visibility rule; or one `<item>` in
`now_playing_menu.xml:21-32` if toolbar chrome is acceptable.
3. Optional: drop the success popups, merge the add/delete pair.

**Session-command variant.** The session already hosts custom commands and buttons (`MediaService.kt:107-116`, `:134`, `:184-186`,
`:192-212`, constants `:253-254`), so a `favorite` command would reach the notification and head units, with the state in the player
instead of fragment-private fields (`NowPlayingFragment.kt:102-104`) — one source of truth, which this draft lacks. Costs: the custom
layout must be re-set on every track change, and a session exists only once started for the active player (`MainActivity.kt:234-238`,
`LocalPlaybackService.kt:135`). An addition, not a replacement for an on-screen control.

## 5. Contract/ripple

- New API `ConnectionHelper.kt:324-334`; only callers `NowPlayingFragment.kt:421`, `:424`, `:474`, `:476`, `:490` (`git grep` per
symbol). Each request/response used once: `CurrentTrackInfoRequest.kt:27-31` + `CurrentTrackInfoResponse.kt:24-31` →
`ConnectionHelper.kt:325`; `FavoritesExistsRequest.kt:22-23` + `FavoritesExistsResponse.kt:24-26` → `:328`;
`FavoritesAddRequest.kt:26-27` → `:331`; `FavoritesDeleteRequest.kt:26-27` → `:334`. Strings `values/strings.xml:30-34`:
`menu_favorite_add` → `fragment_nowplaying.xml:66`, `NowPlayingFragment.kt:455`; `menu_favorite_remove` → `:453`;
`favorite_added` → `:500`; `favorite_removed` → `:502`; `favorite_error` → `:485` (unreachable per §2.1). No `values-*`
counterpart (`values-de` is already 105/111 strings); the only draft adding strings. New id `favorite` used only at
`NowPlayingFragment.kt:240`, `:443`, `:444`, `:451`; listener method declared `:89`, implemented `MainActivity.kt:324-326`,
calls `DisplayStatusFragment.kt:75`.

## 6. Concurrency/lifecycle

`favoriteUpdateJob` (`:100`) serves both fetch and write, and `updateFavoriteState` (`:413`) and `toggleFavorite` (`:466`) both cancel
it: tap → optimistic update (`:468`) → track change → `:280` refresh cancels the in-flight **write** — if that lands before
`client.publish` (`ConnectionHelper.kt:448-449`) the tap is silently lost; if it went out, it lands but the confirmation (`:497-506`)
is cancelled; either way the user is not told. A separate slot for the write removes the ambiguity; keep the `:481`/`:492` guards
either way. No leak: `lifecycleScope` jobs, bounded requests (`ConnectionHelper.kt:450-452`), and touching `binding` matches the
existing `sliderDragUpdateJob` (`:250-254`, `ViewBindingFragment.kt:29-43`). The §2.1 failure also disconnects
(`ConnectionHelper.kt:421-427` → `disconnectInternal`, `:500-511`) while `currentSongUrl` survives: the button keeps offering a
toggle on a dead connection.

## 7. Style

Measured over the diff (`git diff -U0 … | awk '/^\+[^+]/ {print length(substr($0,2))}'`): longest added Kotlin line **99** columns
(`CurrentTrackInfoRequest.kt:24`), longest XML **98** excluding `pathData`; the two `pathData` lines are 209
(`ic_favorite_24dp.xml:26`) and 389 (`ic_favorite_border_24dp.xml:26`) — XML, outside `.editorconfig`'s `[*.{kt,kts}]`
(`.editorconfig:1-2`), and upstream drawables run to 1228. No trailing whitespace or tabs. Both request KDocs
(`CurrentTrackInfoRequest.kt:23-25`, `FavoritesAddRequest.kt:22-25`) are the right tone; the `requestOrNull` KDoc (`:397-401`) and
"avoid it flickering" (`:438-439`) are not. No drive-by reformatting beyond the wrapper re-indent.

## 8. Tests

A UI test is not feasible — there is no Robolectric or instrumentation, and `app/src/test` holds only `ListResponseTest.kt` and
`FlacMetadataCachingDataReaderTest.kt` — and should not be asked for. A plain-JVM decode test for the two new responses is cheap and
pins what fails silently: the `playlist_loop` key (H4) and the 0/1 `exists` (`Serializers.kt:30-37`); worth it only with a captured
payload. For §2.1, the evidence is a device run: disconnect while tapping, check the rollback and the popup.

## 9. What to ask the maintainer for

- Placement: transport row (his wording) / toolbar menu item / media-session command — the draft does none of these.
- If it lands: fix §2.1 and hide or grey the button until the state is known (§2.2).
- Cost: one `status` request per track change — acceptable, or the URL in the status model (3 existing files, URL on every tick,
robust identity as a side effect)?
- Confirmations and the post-write re-read (`:489-491`): keep, or drop for a 12-file diff? The session command is an addition for
notification/head-unit reach, not a replacement.
