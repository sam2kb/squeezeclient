# Review notes — draft PRs

Sidecar reviews of the drafts in `../pr/`, written against upstream `6dacef7` (still the current
upstream HEAD). Each file is a critical read of one branch: what holds up, what does not, and the
smallest correct form of the change — so the maintainer can either take the fix or reimplement it
their own way from the insight.

Code is not modified by these reviews; they are notes only.

## Status

| branch | review | headline |
| --- | --- | --- |
| `fix/cometd-request-while-disconnected` | [`fix-cometd-request-while-disconnected.md`](fix-cometd-request-while-disconnected.md) | Sound fix; narrow the `catch (e: Exception)` in the paging source, and either broaden or reword the initial-request catch |
| `fix/local-position-display` | pending | |
| `fix/mediasession-next-at-playlist-end` | pending | |
| `fix/mediasession-pending-track` | pending | |
| `fix/position-after-disconnect` | pending | |
| `fix/session-reannounce` | pending | |
| `fix/slider-drag` | pending | |
| `fix/volume-device-volume-fades` | pending | |
| `feature/nowplaying-favorite-toggle` | pending | |
| stream start on resume (`f6fc998`, integration branch) | pending | |
| position a seek asked for (inside `fix/position-after-disconnect`) | pending | |

## Verification done here (independent of `check-report.txt`)

- toolchain: Temurin JDK 21 + Android SDK platform 37.0 / build-tools 37.0.0, Gradle 9.6.1 via the
  repo's own wrapper; each branch is checked out alone and compiled with
  `:app:compileFossDebugKotlin`.
- `check-report.txt` is the author's run on Windows; the compile results here are a second,
  independent confirmation.
