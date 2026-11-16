# Antite

An Android browser built on GeckoView, the engine of Firefox for Android.

The APK is a small shell. On first start it downloads the engine: the GeckoView native libraries come straight from
Mozilla's Maven repository (`maven.mozilla.org`), and a small adapter package comes from this repository's releases.
Every file is pinned by SHA-256 in a signed index, so engine updates arrive without reinstalling the app and without
trusting the download mirrors.

## Features

- Tabs with a grid or list switcher, tab categories with their own names, colors and pictures, session restore
- Incognito tabs, optionally behind the device unlock, with screenshots blocked
- Bookmarks, history, recently closed tabs, a new tab page with pinned and frequent sites
- Password manager, payment cards and addresses, encrypted with the Android Keystore
- Secure storage: pictures and videos moved out of downloads into the browser's private folder, with a built-in viewer
- uBlock Origin, installed on first start
- Privacy settings: HTTPS-only, DNS over HTTPS, tracking and fingerprinting protection, WebRTC IP hiding, search
  suggestions off in incognito
- A settings page that lists every request the browser makes on its own, with a switch for each
- Find in page, save as PDF, pull to refresh, picture-in-picture for full-screen video, background media playback
- Downloads with progress notifications
- Engine and app updates from signed sources, with automatic rollback if a new engine fails to start
- Encrypted export and import of all browser data
- Interface in Russian and English ([Fluent](https://projectfluent.org/) files in [`l10n/`](l10n))

Requires Android 11 (API 30) or newer on arm64-v8a, armeabi-v7a or x86_64. One APK fits all three.

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
./gradlew :app:assembleDebug               # shell APK, app/build/outputs/apk/debug/
./gradlew :engine-gecko:adapterPackage     # engine adapter, engine-gecko/build/outputs/engine/
```

Release builds are signed only when `antite.keysDir` in `~/.gradle/gradle.properties` points to the signing keys;
otherwise they come out unsigned.

`tools/publish/publish_engine.py` builds the adapter, pins Mozilla's per-ABI AARs, writes and signs `engines.json`.
`tools/publish/serve_local.py` serves the current build to a device for the browser's debug install mode.

## Tests

```
./gradlew :app:testDebugUnitTest           # JVM tests
./gradlew :app:assembleDebugAndroidTest    # instrumented tests, run with adb shell am instrument
```

`tools/e2e/` holds end-to-end scenarios that drive a device or emulator over adb, for example
`python3 -I tools/e2e/upload.py --serial HOST:PORT`. Each script says in its header what it checks and what it needs.
Test pages are served from the computer over `adb reverse`; examples use `example.com` and reserved names only.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Contributions are accepted under the contributor agreement described there.

## License

[Mozilla Public License 2.0](LICENSE). GeckoView is also distributed under the MPL 2.0.
