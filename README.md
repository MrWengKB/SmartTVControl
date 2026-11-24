# SmartTVControl
An easy-to-use app that acts as a remote control for smart TVs. Support for different TV OSes will be added incrementally.

It starts from [webOSControl](https://github.com/MrWengKB/webOSControl) v0.1.9, with its full history. See [docs/HANDOVER.md](docs/HANDOVER.md) for the plan to add more brands.

A native Android remote control for LG webOS TVs and Google TV / Android TV, written in Kotlin with Jetpack Compose.

## Supported TVs

| | LG webOS | Google TV / Android TV |
| --- | --- | --- |
| Found on Wi-Fi | SSDP | mDNS (`_androidtvremote2._tcp`) |
| Pairing | Accept the prompt on the TV | Type the 6-character code the TV shows |
| Arrows, OK, Back, Home, Settings, Info, colour keys, numbers | Yes | Yes |
| Volume, mute, channels, media keys | Yes | Yes |
| Power | Off, and on with Wake-on-LAN | Power key (on and off) |
| Apps | Lists the TV's installed apps | A list of common apps (the TV doesn't share its list) |
| Inputs | Lists and switches inputs | Presses the Input key |
| Typing | Yes | Yes |
| Touchpad | Yes | No (Google TV has no pointer) |

The app works out which system a TV runs. TVs found on the Wi-Fi are detected by the way they
advertise themselves. For a typed IP, the app checks which ports answer (3000/3001 for webOS,
6466/6467 for Google TV), and if the TV doesn't answer it asks you to choose.

## Features

- Finds TVs on your Wi-Fi automatically (SSDP for webOS, mDNS for Google TV), or connect by IP address.
- Pairs once (on-screen prompt for webOS, code for Google TV) and remembers it, so later connections are silent.
- Power off, and power on with Wake-on-LAN (the app learns the TV's MAC address the first time it connects).
- Volume up/down/mute (the phone's volume keys also work), channel up/down.
- D-pad with OK, Back, Home, Settings, Info, color keys and a number pad.
- Touchpad mode for the TV's pointer, with a scroll strip.
- Media controls (play, pause, stop, rewind, fast forward).
- Launch any installed app and switch HDMI inputs.
- Type text into the TV's focused text box from the phone keyboard.

## Install

Install the APK from the latest entry on the Releases page; new releases install over it as updates.

Every push to `main` also builds a debug APK in GitHub Actions. It installs as a separate app, "Smart TV Remote (debug)", because it is signed with a throwaway key. Open the latest run of
**Android build** under the repository's Actions tab, download the `SmartTVControl-debug-apk`
artifact, unzip it and install the APK on your phone (allow installs from unknown sources).

### Versions and releases

[`versions.md`](versions.md) is the version history and the single source of the version
number. To release, add a new section at the top and push to main:

```md
# v0.2.0
- What changed
```

The **Release** workflow then builds the APK and publishes it on the Releases page as `v0.2.0`,
with that section as the release notes. Pushes that don't add a new version publish nothing.
Use semantic versioning: bump the last number for fixes, the middle one for new features.
Android's `versionCode` is derived from the version (1.2.3 becomes 1002003), and the app shows
the version at the bottom of the TV list.

So that each release installs as an update over the previous one, sign them all with the same
key. Create one once and add it as repository secrets (Settings > Secrets and variables > Actions):

```sh
keytool -genkeypair -v -keystore release.jks -alias smarttv -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # value for SIGNING_KEYSTORE_BASE64
```

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | the base64 output above |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `smarttv` (or the alias you chose) |
| `SIGNING_KEY_PASSWORD` | the key password |

Keep `release.jks` safe; without the secrets the release is signed with a throwaway debug key.

To build it yourself you need JDK 17 and the Android SDK:

```sh
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :core:test                # protocol tests, no Android SDK needed
```

Requires Android 8.0 (API 26) or newer.

## TV setup

- Phone and TV must be on the same network.
- **webOS:** on first connect the TV asks whether to allow the device; accept it with the TV remote.
- **Google TV / Android TV:** on first connect the TV shows a 6-character code; type it into the app.
- For power-on, enable **Settings > General > Devices > TV Management > Turn on via Wi-Fi**
  (called *Mobile TV On* on some models). Wake-on-LAN is most reliable when the TV uses Ethernet.

## How it works

The `core` module is a plain Kotlin library with no Android dependencies:

- `SsdpDiscovery` sends an M-SEARCH for `urn:lge-com:service:webos-second-screen:1` and reads the
  TV's name from its UPnP description.
- `WebOsClient` speaks LG's SSAP protocol: JSON over a WebSocket on `wss://<tv>:3001` (2018+
  firmware), falling back to `ws://<tv>:3000`. It registers with LG's signed pairing manifest,
  then sends `request`/`subscribe` messages such as `ssap://audio/volumeUp`.
- `PointerSocket` drives the separate pointer input socket used for d-pad buttons and the touchpad.
- `WakeOnLan` sends the magic packet for power-on.
- `AndroidTvPairing` and `AndroidTvClient` speak the Android TV Remote protocol v2: protobuf
  messages over TLS, with pairing on port 6467 and the remote on port 6466. The app makes its own
  self-signed client certificate (`AndroidTvIdentity`) once; the TV trusts it after pairing.
- `TvProbe` tells the two systems apart for a typed IP from the ports that answer.

The `app` module is the Compose UI (`RemoteScreen`, `DevicesScreen`), `RemoteViewModel`, and
`AndroidTvDiscovery` (mDNS through Android's NsdManager).

The TV presents a certificate from LG's private CA on port 3001, so the app accepts any certificate
for these LAN connections. The pairing manifest in `core/src/main/resources` comes from the
[lgtv2](https://github.com/hobbyquaker/lgtv2) project (MIT).
