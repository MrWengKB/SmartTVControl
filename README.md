# webOSControl

A native Android remote control for LG webOS TVs, written in Kotlin with Jetpack Compose.

## Features

- Finds TVs on your Wi-Fi automatically (SSDP), or connect by IP address.
- Pairs once with the TV's on-screen prompt and remembers the key, so later connections are silent.
- Power off, and power on with Wake-on-LAN (the app learns the TV's MAC address the first time it connects).
- Volume up/down/mute (the phone's volume keys also work), channel up/down.
- D-pad with OK, Back, Home, Settings, Info, color keys and a number pad.
- Touchpad mode for the TV's pointer, with a scroll strip.
- Media controls (play, pause, stop, rewind, fast forward).
- Launch any installed app and switch HDMI inputs.
- Type text into the TV's focused text box from the phone keyboard.

## Install

Every push to `main` builds a debug APK in GitHub Actions. Open the latest run of
**Android build** under the repository's Actions tab, download the `webOSControl-debug-apk`
artifact, unzip it and install the APK on your phone (allow installs from unknown sources).

To build it yourself you need JDK 17 and the Android SDK:

```sh
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :core:test                # protocol tests, no Android SDK needed
```

Requires Android 8.0 (API 26) or newer.

## TV setup

- Phone and TV must be on the same network.
- On first connect the TV asks whether to allow the device; accept it with the TV remote.
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

The `app` module is the Compose UI (`RemoteScreen`, `DevicesScreen`) and `RemoteViewModel`.

The TV presents a certificate from LG's private CA on port 3001, so the app accepts any certificate
for these LAN connections. The pairing manifest in `core/src/main/resources` comes from the
[lgtv2](https://github.com/hobbyquaker/lgtv2) project (MIT).
