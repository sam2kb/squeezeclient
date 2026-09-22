# Review — `fix/cometd-request-while-disconnected` (`1c453ab`)

**Reviewed against** upstream `6dacef7`, which is *still* the current upstream HEAD (no drift since the
drafts were cut), branch tip `1c453ab`, diff 2 files +14/−4.

**Independently verified here** (not just taken from `check-report.txt`): the branch was checked out
alone and compiled with AGP 9.2.1 / JDK 21 — `:app:compileFossDebugKotlin` → `BUILD SUCCESSFUL`. So the
standalone-build claim holds off the author's machine. Added lines are all ≤ 98 chars, inside the
100-col limit from `.editorconfig`.

**Verdict.** The diagnosis is right, the evidence is a real crash log, and the fix is in the right
place. One line over-reaches and should be narrowed before this goes upstream; the rest is wording and
one pre-existing latent bug that is worth fixing while the line is being touched anyway.

---

## 1. `catch (e: Exception)` is wider than the change needs — narrow it

`ui/common/BasePagingListFragment.kt:134`.

The reason for touching this line is sound: the request path used to signal "not connected" with
`IllegalStateException`, which this catch relied on; once that signal becomes a `CometdException`, the
old catch lets it through and the process dies. But `Exception` also swallows failures that are *bugs*,
not disconnect symptoms — `NullPointerException`, `ClassCastException`, a `SerializationException` from
a response the model no longer matches. All of those stop being crashes and become a silently empty
list with a retry button, i.e. the app lies instead of failing loudly where a developer would see it.

The narrow form keeps the original intent — only the failures this path can legitimately produce — and
is barely longer:

```kotlin
} catch (e: IllegalStateException) {
    LoadResult.Error(e)
} catch (e: CometdClient.CometdException) {
    LoadResult.Error(e)
}
```

`IllegalStateException` still has a live producer right here: `if (!isAdded) throw
IllegalStateException("Fragment not attached")` at line 95.

Supporting argument for the PR text: a broad `catch (e: Exception)` is *not* house style in this
codebase — outside `DownloadWorker` there is not a single other instance in `app/src/main`.

## 2. `CancellationException` is swallowed — pre-existing, one line while you are in this catch

`PagingSource.load` is `suspend` and Paging expects cancellation to propagate.
`kotlinx.coroutines.CancellationException` is a `java.util.concurrent.CancellationException`, which
extends `IllegalStateException` — so **both** the old catch and the new one swallow it and return
`LoadResult.Error` where the library expects the load to end as cancelled (typically when PagingData is
re-collected, e.g. via `cachedIn`). Not introduced by this PR, but this is the one line where fixing it
costs nothing:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalStateException) {
    LoadResult.Error(e)
} catch (e: CometdClient.CometdException) {
    LoadResult.Error(e)
}
```

## 3. The comment promises more than the catch delivers

`cometd/ConnectionHelper.kt:602-608`. The comment says *"a failed request must not be fatal"*, but only
`CometdException` is caught. The same `requestMethod()` can also throw
`kotlinx.serialization.SerializationException`: `doRequestWithResult` is `publishOneShotRequest(...)`
**plus** `json.decodeFromJsonElement<T>(...)` (lines 373-375), and the player status lambda additionally
runs `asModelStatus(connectionHelper.json)` (line 548-550). A response the model cannot decode still
escapes `onEach { }` into `launchIn(...)` and is fatal, exactly as before.

Either fix it or say what is actually true:
- catch `Exception` (rethrowing `CancellationException`) if the intent really is "nothing from the
  initial request may kill the process"; or
- keep `CometdException` and reword to "a request that fails because we are not connected must not be
  fatal".

As written, a reviewer will read a stronger guarantee than the code gives.

## 4. Contract check the brief asked for: the exception-type change is complete — suspicion cleared

`REVIEW-BRIEF.md` lists "whether the exception type is the right contract for the other callers" as the
thing to be suspicious about. Checked exhaustively:

- `IllegalStateException` is caught in exactly **one** place in the app — this one, and it is handled.
- Every other `IllegalStateException` in the tree is an unrelated local throw (`FragmentExtensions:36`,
  `PreferenceExtensions:67,93`, `SlimprotoSocket:139`, `MainContentContainerFragment:474`, and the
  `isAdded` check above) — none of them on the request path.
- Request failures elsewhere are already `CometdException` (`ConnectionHelper` 148, 160, 170, 403, 426,
  463) or go through `runCatching`, which takes `Throwable`.

So no other caller is left catching the old type, and nothing else needs to move with this change. This
is worth one sentence in the PR text, because it is the first question a reviewer will have.

## 5. Smaller points

- **Message text.** `CometdException("Not connected to the server")` — `CometdClient` already uses the
  shorter `CometdException("Not connected")` for the same state (`CometdClient.kt:108,117`). Matching it
  keeps logs and greps consistent.
- **No test, and none is warranted.** The tree has tests only for `ListResponse` and
  `FlacMetadataCachingDataReader`; there is no `ConnectionHelper`/cometd test at all, and this failure is
  a suspend/flow race against a live client with no seam to inject. A crash log plus a reproduction is
  the right evidence here — asking for a unit test would mean inventing test infrastructure the repo
  does not have.

## 6. One behaviour worth a sentence in the PR text

If the initial request fails **because the client is already gone**, the fallback subscription is
skipped too: `connectionHelper.client?.subscribe(responseChannel)` (line 611) is null-safe and silently
does nothing. Recovery then depends on a fresh `PlayerState` being built after the teardown. That does
happen — `disconnectInternal` cancels and clears `playerStates` and is also the only place that sets
`client = null`, so the two never drift far apart — which is why this is an observation rather than a
defect. But "the subscription delivers the state once we are connected" is only true when the client
object still exists at that moment; the comment reads as if it were unconditional.

---

## For the maintainer — the short form

Real bug, real crash log, right file, right place, no other callers affected. What is worth asking for
before merge:

1. narrow the paging catch to `IllegalStateException` + `CometdException` (finding 1);
2. either broaden or reword the initial-request catch (findings 2–3).

With those, this is a 2-file, ~+18/−4 change that fixes a reproducible FATAL without touching anything
else. If the maintainer prefers to fix it differently, the load-bearing insight is only this: **"not
connected" is a legitimate state, so it must not be signalled with the same exception type as a
programming error** — the rest is placement.
