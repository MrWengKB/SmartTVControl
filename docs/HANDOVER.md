# webOSControl handover

The state of the webOS remote app as of **v0.1.9 (24 Nov 2026)**, written for starting a new
multi-brand remote project. This repo (`MrWengKB/webOSControl`) stays maintained for LG webOS.

## 1. What the app does today

A native Android remote for LG webOS TVs, written in Kotlin and Jetpack Compose. It requires
Android 8.0 or newer (minSdk 26, targetSdk 35).

- **Finding and pairing:** finds TVs on the Wi-Fi (SSDP) or by typed IP. You pair once, and the
  key is remembered for each TV.
- **Power:** off through the TV's protocol; on with Wake-on-LAN, using the MAC address learned on
  the first connection.
- **Sound and channels:** volume up/down and mute (the phone's volume keys work too), and
  channel up/down.
- **Remote keys:**
  - Arrow keys with OK, plus Back, Home, Settings and Info.
  - Colour buttons, kept plain with no labels.
  - A number pad.
- **Touchpad mode:** moves the TV's pointer, with a scroll strip on the right.
- **Media, apps and inputs:**
  - Media keys.
  - An app launcher, where long-pressing an app shows "use the LG remote to uninstall".
  - Input switching.
- **Typing:** typing on the TV works, including YouTube search. The keyboard opens by itself when
  a text box is focused, and every letter is sent as you type.
- **Update check:** on launch the app checks GitHub Releases. It offers **Update** (downloads the
  APK) or **Ignore** (skips that version). There is also a "Check for updates" button on the TV
  list.

Kevin confirmed on his real TV in Singapore that everything except uninstall works.

## 2. Architecture and key files

```
core/   pure Kotlin/JVM library (no Android), unit-tested without an Android SDK
  WebOsClient.kt     SSAP WebSocket client: connect/pair, request, subscribe, pointer socket
  TvCommands.kt      Ssap URIs, TvButton names, parsers, extension functions (volumeUp, launchApp…)
  SsdpDiscovery.kt   M-SEARCH + UPnP description fetch (friendlyName, modelName)
  WakeOnLan.kt       magic packet
  LanHttp.kt         OkHttp client that trusts the TV's self-signed cert (LAN only)
  resources/.../pairing.json   LG signed pairing manifest (from lgtv2, MIT)
  test/              fake TV on MockWebServer, SSDP over loopback UDP, parsers (~20 tests)

app/    Android UI
  RemoteViewModel.kt  all state (UiState) and actions; owns WebOsClient + PointerSocket
  TvStore.kt          SharedPreferences: paired TVs (host, name, clientKey, mac), last used
  UpdateChecker.kt    GitHub "latest release" check, semver compare, ignored version
  MainActivity.kt     Compose host, volume keys, connect on start / disconnect on stop
  ui/RemoteScreen.kt  remote layout, d-pad, touchpad, rockers
  ui/DevicesScreen.kt TV list, manual IP, version + "Check for updates"
  ui/Sheets.kt        Apps, Inputs, Keyboard, Numbers bottom sheets
  ui/UpdateDialog.kt  Update / Ignore dialog
```

Design choices worth keeping:
- **Protocol code is separate from the UI.** It can be tested on any machine without the Android
  SDK. The tests run a fake TV, so protocol changes can be checked without a real set.
- **Connection lifecycle:** the app connects when it comes to the foreground and disconnects in
  the background. This saves battery and avoids stale sockets.
- **Typed text is queued.** It goes through a single ordered queue, so fast typing doesn't arrive
  scrambled.

## 3. webOS protocol notes (learned and verified)

**Discovery:** send SSDP M-SEARCH to `239.255.255.250:1900` with
`ST: urn:lge-com:service:webos-second-screen:1`.
- Reply `LOCATION` points to UPnP XML, where `<friendlyName>` gives the TV's name.
- Android needs a `WifiManager.MulticastLock` while searching.

**Connection:** try `wss://<ip>:3001` first (2018+ firmware), then fall back to `ws://<ip>:3000`.
- Port 3001 uses LG's own CA with no hostname match, so the client trusts any certificate for
  this LAN connection only.
- Cleartext must be allowed for `ws://` and for the UPnP XML (network_security_config).

**Pairing:** send `{"type":"register","id":…,"payload":{…pairing.json…, "client-key":<saved>}}`.
1. The first time, the TV replies `type:"response"` with `pairingType:"PROMPT"` and shows the
   prompt.
2. After the user accepts, it replies `type:"registered"` with `payload["client-key"]`. Store
   that key per TV.
3. On later connections, sending the stored key skips the prompt.

**Requests and subscriptions:**
- Requests are `{"type":"request","id","uri","payload"}`. Errors come back as `type:"error"` or as
  `payload.returnValue:false` with `errorText`.
- Subscriptions are `type:"subscribe"`, which keep sending replies with the same id. Stop them
  with `type:"unsubscribe"`.
- Volume replies come in two shapes: the old `{volume, muted}` and webOS 5+
  `{volumeStatus:{volume, muteStatus}}`.

**Endpoints in use:**
- **Power and system:**
  - `system/turnOff`: the TV may drop the socket before replying, so treat a timeout as success.
  - `system/getSystemInfo`
  - `com.webos.service.connectionmanager/getinfo`: MAC address in `wiredInfo`/`wifiInfo`.
  - `com.webos.service.update/getCurrentSWInformation`: fallback, where `device_id` is the MAC.
- **Sound and channels:** `audio/volumeUp|volumeDown|setVolume|setMute|getVolume` (subscribe for
  live volume), `tv/channelUp|channelDown`.
- **Apps:** `com.webos.applicationManager/listLaunchPoints` (has `removable`),
  `system.launcher/launch {id}`.
- **Inputs:** `tv/getExternalInputList`, `tv/switchInput {inputId}`.
- **Media:** `media.controls/play|pause|stop|rewind|fastForward`.
- **Notifications:** `system.notifications/createToast`.

**Typing:** the TV ignores `insertText` unless the client holds a subscription to
`ssap://com.webos.service.ime/registerRemoteKeyboard`. This was the real fix for YouTube search.
- Subscribe on connect.
- Updates carry `currentWidget.focus`, plus `hiddenText` for password fields.
- Send edits with `insertText {text, replace:0}`, `deleteCharacters {count}` and `sendEnterKey`.
- The app compares the text before and after each change, then deletes from the end and inserts.

**Pointer socket:** request `com.webos.service.networkinput/getPointerInputSocket`, which returns
`socketPath`, and open a second WebSocket to it.
- Messages are `key:value` lines ending with a blank line, for example
  `type:button\nname:UP\n\n`, `type:move\ndx:3\ndy:-4\ndown:0\n\n`, `type:click\n\n` and
  `type:scroll`.
- Button names: UP DOWN LEFT RIGHT ENTER BACK HOME EXIT MENU INFO GUIDE RED GREEN YELLOW BLUE DASH,
  and 0–9.

**Wake-on-LAN:** send 6×0xFF followed by the MAC 16 times, over UDP to 255.255.255.255 and the
/24 subnet broadcast, on ports 9 and 7. The TV needs "Turn on via Wi-Fi" (Mobile TV On) enabled.

## 4. Tried and dropped

| Attempt | Result |
| --- | --- |
| Uninstall via `ssap://com.webos.appInstallService/remove` | TV answered **404** (service not exposed to SSAP). No other remote route found. Long-press in Apps now just says "use the LG remote". |
| Emulate holding the remote button (repeat pointer `move … down:1` while finger held on OK/touchpad) to enter app-list edit mode | Did nothing on Kevin's TV. Removed in v0.1.7. |
| Labels + ⓘ beside colour buttons (red = Subtitles, yellow = Dual sound in Live TV, Singapore) | Redundant: pressing Up in Live TV already shows them. Rolled back in v0.1.9. |
| Showing results in a snackbar while a bottom sheet is open | The snackbar is hidden behind the sheet. Use a dialog for anything shown over a sheet. |

## 5. Build, release and signing

- **Build:**
  - Gradle 8.14.3 wrapper, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, OkHttp 4.12,
    kotlinx-serialization 1.7.3.
  - The cloud sandbox can't reach Google's Maven, so APKs are built in GitHub Actions. Core tests
    can run locally with only Maven Central.
- **`android.yml`:** runs on every push and PR. It runs the core tests, then builds the debug and
  release APKs (so R8/minify problems show up early) and uploads the debug APK as an artifact.
- **Version source:** `versions.md`. Its first heading `# vX.Y.Z` is the app version.
  - `versionCode = major*1_000_000 + minor*1_000 + patch`.
  - The app shows the version on the TV list.
- **`release.yml`:** runs on a push to main that touches `versions.md`, or on a manual run.
  - It reads the top version and skips if release `vX.Y.Z` already exists.
  - Otherwise it builds a signed release APK and publishes GitHub Release `vX.Y.Z`, with the top
    section of `versions.md` as notes and `webOSControl-vX.Y.Z.apk` attached.
  - The tag goes on the default branch. Pinning it to `$GITHUB_SHA` gave a 403 on re-runs after a
    workflow file changed.
  - Modelled on HanziQuery's `version-history.md` flow, but with one release per version instead
    of a rolling `apk-latest`.
- **Signing secrets** (repo Settings › Secrets › Actions): `SIGNING_KEYSTORE_BASE64`,
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS` (`webos`).
  - The workflow trims pasted whitespace and accepts line-wrapped base64.
  - It checks the keystore with `keytool` and prints only fingerprints and lengths.
  - The keystore and its password are kept privately by Kevin and are not in this doc. Every
    release must be signed with that same key, or updates won't install.
- **Debug builds** use `applicationIdSuffix ".debug"` ("webOS Remote (debug)"). They are signed
  with a throwaway CI key, so they must never share the release app id. That clash caused the
  "signatures don't match" install error.
- **Update check:** `GET https://api.github.com/repos/MrWengKB/webOSControl/releases/latest`
  without auth (the repo is public).
  - It compares `tag_name` with `BuildConfig.VERSION_NAME`.
  - It offers the first `.apk` asset and shows `body` as the notes.
  - An ignored version is stored in prefs and still shown by a manual check.
  - Debug builds don't check automatically.

## 6. Suggestions for the multi-brand project

1. **Use one app with one remote UI, and a separate driver for each brand.** Define a small
   interface in a pure-Kotlin `core` module, for example:
   ```kotlin
   interface TvDriver {
       val capabilities: Set<Capability>   // POWER_OFF, WOL, VOLUME, CHANNELS, DPAD, POINTER, TEXT, APPS, INPUTS, MEDIA
       suspend fun connect(saved: SavedTv): SavedTv  // returns updated credentials
       suspend fun key(key: RemoteKey)                // UP/DOWN/OK/BACK/HOME/VOL_UP/…
       suspend fun text(edit: TextEdit)
       fun keyboardFocus(): Flow<Boolean>
       suspend fun apps(): List<AppInfo>; suspend fun launch(id: String)
       …
   }
   ```
   Move this repo's `WebOsClient` behind a `WebOsDriver`. The UI should hide buttons a TV's
   capabilities don't support, instead of showing errors.
2. **Use one discovery service with a detector per brand**, and save the brand with each TV:
   - webOS: SSDP `urn:lge-com:service:webos-second-screen:1`.
   - Android/Google TV: mDNS `_androidtvremote2._tcp`.
   - Roku: SSDP `roku:ecp`.
   - Samsung Tizen: SSDP plus `http://<ip>:8001/api/v2/`.
3. **Android/Google TV driver:** use the "Android TV Remote v2" protocol. It pairs over TLS on port
   6467 with a code shown on the TV, then sends commands as protobuf on port 6466. That protocol
   includes key presses, text input and app links. This is the largest new piece of work, so
   build it first, behind the interface, with a fake-server test like `WebOsClientTest`.
4. **Keep what worked here:**
   - Protocol modules that run without Android, tested against fake TVs.
   - The `versions.md` release flow, signing secrets and `.debug` id suffix.
   - The GitHub update check.
   - Copy `.github/workflows/*` and the signing block in `app/build.gradle.kts` across. Use a
     **new keystore** only if the new app has a new application id.
5. **Storage:** move from SharedPreferences JSON to Room or DataStore once there are brands with
   different credential types (webOS client key, Android TV client certificate, Samsung token).

## 7. Open items in this repo

- GitHub Actions warns that `actions/*@v4` and `setup-java@v4` are on deprecated Node 20. Bump
  them to v5 when convenient.
- No instrumented/UI tests; only the core protocol is unit-tested.
- There are no green/blue Live TV labels, by choice.
