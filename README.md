# Antite

An Android browser built on GeckoView, the engine of Firefox for Android.

The APK is a small shell. On first start it downloads the engine: the GeckoView native libraries come straight from
Mozilla's Maven repository (`maven.mozilla.org`), and a small adapter package comes from this repository's releases.
Every file is pinned by SHA-256 in a signed index, so engine updates arrive without reinstalling the app and without
trusting the download mirrors.

## Features

- Tabs with a grid or list switcher, session restore, incognito tabs
- Bookmarks, history, recently closed tabs, a new tab page with pinned sites
- Password manager, payment cards and addresses, encrypted with the Android Keystore
- uBlock Origin, installed on first start
- Privacy settings: HTTPS-only, DNS over HTTPS, tracking and fingerprinting protection, WebRTC IP hiding
- A settings page that lists every request the browser makes on its own, with a switch for each
- Offline page translation, find in page, save as PDF, downloads with progress notifications
- Engine updates from signed sources, with automatic rollback if a new engine fails to start
- Encrypted export and import of all browser data

Requires Android 11 (API 30) or newer, arm64-v8a or x86_64.

## Engine index

The built-in index is [`engines.json`](engines.json) in the root of this repository, signed with the publisher key in
[`keys/publisher.pub.pem`](keys/publisher.pub.pem) (ECDSA P-256):

```
SHA-256 fingerprint of the key:
3C:04:75:BC:FF:09:B0:31:D3:47:93:B7:B5:FC:73:5C:B6:5A:06:96:5A:2C:0D:FD:41:97:F7:83:3D:45:86:9D
```

To check an index by hand:

```
openssl dgst -sha256 -verify keys/publisher.pub.pem -signature engines.json.sig engines.json
```

Other sources (an index URL plus its publisher key) can be added in Settings > Updates > Sources or on the first-start
screen. The browser installs an engine only if the index is signed by the source's key and every file matches its hash.

## Building

You need JDK 17 and the Android SDK with the `android-37.2` platform. Put the SDK path in `local.properties`
(`sdk.dir=...`).

```
./gradlew :app:assembleDebug               # shell APKs, app/build/outputs/apk/debug/
./gradlew :engine-gecko:adapterPackage     # engine adapter, engine-gecko/build/outputs/engine/
./gradlew :app:testDebugUnitTest           # JVM tests
```

Release builds are signed only when `antite.keysDir` in `~/.gradle/gradle.properties` points to the signing keys;
otherwise they come out unsigned.

`tools/publish/publish_engine.py` builds the adapter, pins Mozilla's per-ABI AARs, writes and signs `engines.json`.
`tools/e2e/` holds end-to-end scenarios that drive a device or emulator over adb.

## License

[Mozilla Public License 2.0](LICENSE). GeckoView is also distributed under the MPL 2.0.
