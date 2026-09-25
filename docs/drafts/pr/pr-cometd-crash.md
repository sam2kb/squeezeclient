# Crash when a request is submitted while the connection is (being) torn down

**Problem**: the app crashes with a `FATAL EXCEPTION` while reconnecting - toggling Bluetooth or
losing Wi-Fi for a moment is enough. The crash is an `IllegalStateException` without a message,
which makes it hard to attribute from a bug report.

**When exactly**: right after a reconnect, while the CometD connection is still being
(re)established: the player status flow subscribes again and requests its initial state, and by
that time the client or its coroutine scope may already be gone. In the measured case the
connection was re-established 10 s earlier and the crash happened 10 s after that.

**Why** (mechanism):
- `publishOneShotRequest()` reports "not connected" by throwing a bare `IllegalStateException`
  when `client` or `connectionScope` is null - which is a legitimate state, not a programming
  error.
- `createSubscribingFlow()` wraps the *subscription* in `try/catch (CometdException)`, but not the
  initial `requestMethod()` call that runs right before it. The exception therefore escapes the
  `onEach` of a `launchIn(...)` coroutine, which makes it fatal.
- The status flow is (re)started on every reconnect, so this race is hit exactly when the
  connection is flaky.

**Evidence** (from a crash log; package and player identifiers removed):
```
FATAL EXCEPTION: main
Process: <app>, PID: 29124
java.lang.IllegalStateException
    at ConnectionHelper.publishOneShotRequest(ConnectionHelper.kt:410)
    at ConnectionHelper$PlayerState$playerStatusFlow$2.invokeSuspend(ConnectionHelper.kt:673)
    at ConnectionHelper$PlayerState$createSubscribingFlow$1$2.invokeSuspend(ConnectionHelper.kt)
```
Line 410 is `val clientId = client?.clientId ?: throw IllegalStateException()`.

**Reproduction**:
1. Play something with the app connected to the server.
2. Disconnect and reconnect the network (or toggle Bluetooth) a few times, ideally while a request
   is in flight.
3. The app dies in `publishOneShotRequest` instead of just failing that request.

**Suggested minimal change** (implement however you prefer):
- report the "not connected" case as `CometdClient.CometdException("Not connected to the server")`
  instead of a bare `IllegalStateException`, so the existing CometD error handling applies;
- wrap the initial `requestMethod()` of `createSubscribingFlow()` in the same `try/catch` as the
  subscription - the subscription delivers the state once the connection is up, so a failed
  initial request does not need to be fatal.

**Second manifestation** (found by testing on a device, without a car): opening the app or the
playlist while the client is re-handshaking crashed as well. The paging source of the list
(`BasePagingListFragment.ItemSource.load`) catches `IllegalStateException` - which is what the
request path used before - so the new exception type escaped it and the process died. It is the
same problem with a different caller, so it is fixed in the same change: a page load reports any
failure as a load error, so the list can show it and retry.

Diff: 3 files, +34 / -4 (`cometd/ConnectionHelper.kt`, `ui/common/BasePagingListFragment.kt`,
`service/mediasession/SqueezeboxMediaPlayer.kt`).

**Third manifestation** (device report, no car): with the server unreachable, media key presses kept
walking the media session through the playlist - the notification showed title after title while
nothing played, and those presses were the only thing that "did" anything. The log shows the
connection retrying every 10 s (`conn: disconnected, wasConnected=false`) while the keys arrived.
Two things were wrong: a button request that cannot be sent was still fatal to its caller, and the
seek handler published the optimistic track change before the request had a chance to leave the
app. A button press is best-effort now (the failure is logged, not thrown) and a seek command
returns early while the connection is down, so the session keeps reporting the song that is really
playing.

Branch: `fix/cometd-request-while-disconnected` (`e256ce6`, off `upstream/main` `6dacef7`), pushed
to the fork (`origin`), not to upstream.

## For the reviewer

One commit (`e256ce6`) on top of upstream `6dacef7`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). It does not conflict with any other draft.
