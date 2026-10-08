# An empty server address makes the app crash in a loop

**Problem**: the server setup screen can save an empty server address. From then on the app cannot be
started at all - every connect attempt dies with

```
FATAL EXCEPTION: main
Process: de.maniac103.squeezeclient.debug, PID: 17302
java.lang.IllegalArgumentException: Invalid URL host: ""
        at okhttp3.HttpUrl$Builder.parse$okhttp(HttpUrl.kt:1449)
        at okhttp3.HttpUrl$Companion.get(HttpUrl.kt:1741)
        at de.maniac103.squeezeclient.model.ServerConfiguration.getUrl(ServerConfiguration.kt:32)
        at de.maniac103.squeezeclient.cometd.CometdClient.buildRequest(CometdClient.kt:283)
        at de.maniac103.squeezeclient.cometd.CometdClient.handshake(CometdClient.kt:152)
        at de.maniac103.squeezeclient.cometd.CometdClient.connect(CometdClient.kt:91)
        at de.maniac103.squeezeclient.cometd.ConnectionHelper$connect$1.invokeSuspend(ConnectionHelper.kt:157)
        at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
        at android.os.Handler.handleCallback(Handler.java:1070)
```

It is not recoverable from the UI: `MainActivity` only falls back to the server setup screen while
`prefs.serverConfig` is `null`, and a stored-but-empty address is not `null`, so it retries the
connection instead. The service is restarted after the crash, connects again and dies again - a loop
(the device this came from produced 24 crashes in 15 hours, 12 of them within two minutes).

**Why** (mechanism):

1. The connect button of the setup screen is enabled until the first text change - validation
   (`validateInput()`) is only called from the three field listeners, so it never runs on a screen
   that is simply opened and acted on before anything was typed (during server discovery, before the
   address is filled in). Tapping Connect then stores `server_name`/`server_url` as empty strings.
2. `ServerConfiguration.url` builds `"http://$hostnameAndPort"`, and `toHttpUrl()` throws for an
   empty host.
3. `ConnectionHelper.connect()` only catches `CometdException`, so the `IllegalArgumentException`
   escapes the coroutine that `connect()` launched and takes the process down.

**Evidence**: the preferences of the affected installation after the incident -

```xml
<string name="server_name"></string>
<string name="server_url"></string>
```

- with the crash above, and the app log showing the same sequence at every restart: the setup screen
  disconnects the old session, the new activity sees a non-null configuration and calls
  `connect()`, and the process dies ~70 ms later.

**Reproduction**:

1. Open the server setup screen and tap Connect before the address field is filled in (or write an
   empty `server_url` into the app's preferences).
2. Start the app: it crashes in `ServerConfiguration.getUrl` before the setup screen can be reached,
   on every start.

**Suggested minimal change** (implement however you prefer): treat a stored but empty address as no
configuration, so the app falls back to the setup screen instead of retrying, and do not let the
setup screen save an address that the validation rejects (the button should start out disabled).
Two files, +7/-2 (`extfuncs/PreferenceExtensions.kt`, `ui/ServerSetupActivity.kt`).
Branch: `fix/blank-server-address` (`38f6791`, off `upstream/main` `51eb708`), not pushed yet.

## For the reviewer

One commit (`38f6791`) on top of upstream `51eb708`; it builds and lints on its own.

- The crash itself is `okhttp`'s way of saying "this URL has no host"; the fix does not touch it, it
  only makes sure the app never gets there: an empty address is handled like an unconfigured app (a
  case that already exists and is handled), and the setup screen cannot create the state any more.
- `validateInput()` is now also called once while the screen is set up, so the button state matches
  the field contents instead of the layout default.
- Verified on the device: with an empty address stored, the app starts and shows the server setup
  screen instead of crashing; with a valid address it connects as before.
