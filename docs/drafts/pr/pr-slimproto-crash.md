# The app crashes when the slimproto connection breaks while a command is sent

**Problem**: the local player's slimproto socket can go away at any time - the server closes it, the
network changes, the Wi-Fi stack aborts the connection. If a command is being written at that moment,
the resulting `IOException` escapes into the coroutine that sent it and takes the whole app down:

```
FATAL EXCEPTION: main
Process: de.maniac103.squeezeclient.debug, PID: 18708
java.net.SocketException: Software caused connection abort
        at java.net.SocketOutputStream.socketWrite0(Native Method)
        at java.net.SocketOutputStream.write(SocketOutputStream.java:156)
        at okio.OutputStreamSink.write(JvmOkio.kt:57)
        at okio.RealBufferedSink.flush(RealBufferedSink.kt:293)
        at de.maniac103.squeezeclient.service.localplayer.SlimprotoSocket$SocketHolder$write$2.invokeSuspend(SlimprotoSocket.kt)
        at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
```

Seen with the installed build on 2026-09-28 16:34:29 (Wi-Fi was being switched while the local player
was streaming); the app died and had to be started again.

**Why** (mechanism): `SocketHolder.write` is the only socket entry point that is not tolerant of a
broken connection - `readPacket` hands its failures to the caller as a `Result`, `createSocket`
catches `IOException`, but `write` lets the exception out:

```kotlin
suspend fun write(data: ByteBuffer) = withContext(Dispatchers.IO) {
    synchronized(sink) {
        sink.write(data)
        sink.flush()
    }
}
```

Every sender (`sendHello`, `sendStatus`, the setting/stream responses) goes through it, and all of
them are best effort: the connection state is tracked by the read side, which notices a closed socket
immediately and makes the service reconnect.

**Evidence** (device, 2026-09-28): the crash above, at a moment the app was playing and the network
was being switched. After the change the same situations (network off, airplane mode on and off while
playing) leave the process alive and playback continues; the write failure is logged as
`Could not send a command, closing the connection` instead.

**Reproduction**:
1. Play a stream with the built-in player (the app opens its slimproto connection to the server).
2. Switch the network off (or reboot the Wi-Fi stack) while it plays.
3. Watch `adb logcat -b crash`: before the change a `SocketException` in `SocketHolder.write` is a
   FATAL exception; afterwards the connection is closed and the service reconnects.

**Suggested minimal change** (implement however you prefer): catch `IOException` in
`SocketHolder.write`, log it, and close the socket quietly so the read side tears the connection
down (`teardownSocket` closes the same way now - closing a broken socket can throw as well). One
file, +16/-4 (`service/localplayer/SlimprotoSocket.kt`).
Branch: `fix/slimproto-write-crash` (`9539e12`, off `upstream/main` `4c4c526`), pushed to the fork
(`origin`), not to upstream.

## For the reviewer

One commit (`9539e12`) on top of upstream `4c4c526`; it builds and lints on its own (see
`docs/drafts/check-report.txt`). The failure is hard to reproduce on demand - whether a write
actually fails depends on the local network stack aborting the connection - so the verification is
the negative one: with the change no `IOException` can escape that path any more (the write is the
only place that threw; the read path already reports failures to its caller).
