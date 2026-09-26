# iPhotos

iPhotos is an Android gallery for browsing an Apple Photos library. It brings photos, videos, albums, and Live Photos into a familiar gallery experience, with cached previews for faster browsing and original downloads when you need them.

## Features

### Photos and albums

- Browse the photo library and its albums.
- Load cached thumbnails and album information while newer previews continue to load.
- Browse Favorites, Hidden, and Recently Deleted collections.
- Require device authentication before opening Hidden or Recently Deleted.

### Viewing and saving

- View photos with pinch-to-zoom and swipe navigation.
- Play Live Photos on open and replay them with a touch-and-hold gesture.
- Play videos with in-app playback controls.
- Inspect media details such as file name, date, format, dimensions, file size, duration, and orientation.
- Save original media to the device and share it with Android apps.

### Account and sync

- Sign in with an Apple Account and complete two-factor or trusted-device verification when requested.
- Keep the authenticated session encrypted with an Android Keystore-backed key.
- Refresh library and album information automatically about every 12 hours over Wi-Fi.

## Privacy and permissions

iPhotos connects directly to Apple services over HTTPS. It has no iPhotos photo backend or analytics. The account password is used during sign-in and is not saved by iPhotos; the encrypted session remains on the device. The app requests internet access and uses the device's biometric or screen-lock authentication to protect private collections.

The Apple Photos integration relies on private, undocumented service APIs. Apple may change those APIs at any time, which can interrupt sign-in or library access. iPhotos does not ask users to disable account protection or share recovery keys.

## Build

Requirements:

- Android Studio or Android SDK Platform 37
- JDK 17
- Go 1.26 or newer and the Android NDK only when rebuilding the native bridge

The project includes a prebuilt native bridge at `app/libs/icloudbridge.aar`, so Gradle builds do not require Go. The app supports Android 13 / API 33 and newer. The native bridge is packaged for `arm64-v8a` and `armeabi-v7a`; x86 emulator builds require a bridge built for that ABI.

The `prod` and `demo` product flavors share app code under `app/src/main`. The installable offline demo flavor adds its own application, manifest, resources, and sample media under `app/src/demo`. Shared unit tests live under `app/src/test`, with demo-specific tests under `app/src/testDemo`.

```sh
./gradlew :app:testProdDebugUnitTest :app:testDemoDebugUnitTest
./gradlew :app:assembleProdDebug
```

### Offline mock app

To run the gallery with a fictional photo library and no Apple sign-in, install the demo build on a connected device or emulator:

```sh
./gradlew :app:mockApp
```

Open **iPhotos Mock** from the launcher. It is installed alongside the regular app and uses generated sample photos, simulated authentication, and no background sync. It is for UI evaluation only; actions that require Apple services are simulated.

Run the demo-specific unit tests with `./gradlew :app:testDemoDebugUnitTest`.

## Continuous integration and releases

GitHub Actions runs the production and demo unit tests and builds both debug APKs on every push and pull request. The APKs are available as a downloadable workflow artifact.

To rebuild the native bridge, install Go and the Android NDK, then run:

```sh
./scripts/build-icloud-bridge.sh
```

## Contributing

Contributions, bug reports, and ideas are welcome. Run the production and demo unit tests before submitting a change. For bug reports, include the device model, Android version, app version, and sanitized logs. Never include passwords, verification codes, session data, private media, or signed media URLs.

## License

Unless a file or dependency is identified separately, iPhotos is Copyright 2026 Simone Esposito and licensed under the Apache License, Version 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Third-party components retain their own licenses; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
