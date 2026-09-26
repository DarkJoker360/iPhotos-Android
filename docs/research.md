# iCloud Photos client research

Inspected on 2026-09-25. Apple does not publish a supported third-party iCloud Photos client API. The Photos client flow and upload endpoint described here are private protocols and may change without notice.

## Project comparison

| Project | License and role | Auth / ADP / PCS | Photos and sync | Relevance and limit |
| --- | --- | --- | --- | --- |
| [rclone](https://github.com/rclone/rclone), `v1.75.1` | MIT; Go cloud storage client | SRP, 2FA, trusted-device/SMS, and PCS request with a service name; documents ADP for Drive and Photos | Photos libraries, albums, metadata, thumbnails/original download and zone sync-token cache; its Photos filesystem is read-only | Used for Apple session/auth and Photos CloudKit access. A small MIT-attributed local extension exposes the existing `startRank` query as bounded pages for Android; album writes remain unsupported. |
| [icloudpd](https://github.com/icloud-photos-downloader/icloud_photos_downloader) | MIT; actively released command-line downloader | 2FA/session support; its current setup instructions require web access and ask users to disable ADP | Photos download, Live Photo companion/RAW handling, repeated incremental runs; download-oriented | Useful download behavior and operational history. Its published ADP instructions conflict with iPhotos' no-downgrade security requirement, so it is not the auth foundation. |
| [pyicloud](https://github.com/picklepete/pyicloud) | MIT; Python iCloud wrapper | Password and 2FA flows; no verified Photos PCS/ADP flow in the inspected primary README | Photo library/album browsing and downloads; Drive upload is documented, not Photos upload | Reference for legacy web API behavior, not an Android production dependency. |
| [icloudgo](https://github.com/chyroc/icloudgo) | Apache-2.0; Go port of pyicloud | Apple session/cookie authentication; no verified Photos PCS/ADP support documented | Photo downloads and a separate Photos upload command | Its `uploadimagews` path informed an independently implemented, gated upload request. No icloudgo source is copied into this project. |
| [Round Sync](https://github.com/newhinton/Round-Sync) | GPL-3.0 Android file manager powered by rclone | Inherits supported rclone backend authentication; not a purpose-built Photos account UX | General cloud-file browse/sync UX; no Photos-native gallery or MediaStore Camera Upload workflow | Useful Android/rclone integration reference. Its GPL-3.0 application license is not the license chosen for iPhotos. |
| [icloud-photos-sync](https://github.com/steilerDev/icloud-photos-sync) | GPL-3.0 one-way local sync tool | Documents community reverse-engineering for GSA/ADP; not Apple's supported client | iCloud Photos to local filesystem synchronization, with options affecting remote items | Helpful reverse-engineering context; it is a filesystem sync tool, not an Android gallery or bidirectional client. |
| [kei](https://github.com/rhoopr/kei) | MIT; Rust sync-engine project | Its sync-token work is empirical against the private CloudKit API; do not infer auth/PCS coverage from that | Documents `syncToken`, change records, library zones, and incremental sync | Strong current reference for sync-token behavior; not used as an app dependency. Treat its protocol notes as reverse engineering, not Apple guarantees. |

The project comparison is based on upstream repositories and documentation, not a claim that every fork or account configuration has identical behavior. See [rclone's iCloud Drive and Photos docs](https://rclone.org/iclouddrive/), [icloudpd's prerequisites](https://github.com/icloud-photos-downloader/icloud_photos_downloader#icloud-prerequisites), [icloudgo's project page](https://github.com/chyroc/icloudgo), and [kei's sync-token reference](https://github.com/rhoopr/kei/blob/main/docs/synctoken-reference.md).

## Protocol path used by iPhotos

1. `api.Client.Authenticate` performs Apple's SRP authentication. The password is supplied only for the in-memory sign-in call; the bridge replaces the password-bearing client before the method returns.
2. Apple's session performs 2FA. The app exposes Apple's trusted-device code path and SMS fallback.
3. The bridge reconstructs a Photos-scoped client. rclone's `NewPhotosService` requests the Photos web service and handles PCS authorization if Apple says that service needs it.
4. The session JSON is serialized in the bridge and encrypted by Kotlin using AES-GCM with an Android Keystore key. The bridge cache directory contains protocol cache data; session JSON is not stored there.
5. Album/asset records and download URLs are fetched from Apple. Original bytes are downloaded over HTTPS directly to a private app cache file and then streamed to MediaStore.
6. Camera Upload stages a selected MediaStore item privately, hashes it for deduplication, and queues a WorkManager transfer. Standard-account upload uses Apple's separate `uploadimagews`; PCS-required accounts are refused by that path.

The Photos sync client retains rclone's per-zone cache and sync-token state. iPhotos' small local extension now issues one bounded CloudKit `startRank` query per page and stores the returned records in Room; the first gallery page no longer waits for `GetPhotos` to assemble the whole album. The private API's rank ordering and count queries still need validation with live Apple libraries before advertising 100,000-asset scalability. Background/manual sync currently walks the available pages to reconcile Room and remains more expensive than a delta-only Room update.

For image display, the bridge asks Apple's record lookup for `resJPEGThumbRes` and `resJPEGMedRes` separately from the original resource. Those variant field names and photo/video medium variants are present in pyicloud's upstream Photos implementation; unavailable variants fall back to the thumbnail, and video playback remains an explicit original download. No original is fetched for a gallery tile. The resource lookup and returned image sizes still need validation against live Apple accounts.

## Features and limitations

| Area | Current implementation | Validation status |
| --- | --- | --- |
| Sign-in / 2FA | rclone SRP; trusted-device and SMS methods; no saved password | Build-tested; Apple-account flow not exercised here |
| Photos PCS | Photos-specific service construction and PCS session handling | Source-verified; live ADP access unverified |
| Browsing / albums | Private Photos API, `All Photos`, album trees, capture metadata including available GPS/orientation, thumbnails | Build-tested; live library fields unverified |
| Incremental sync | rclone per-zone token/cache; paged CloudKit query; Room records have local pages | Token checks are source-backed; current Room reconciliation enumerates pages; live delta/deletion behavior unverified |
| Original downloads | Apple resource URL lookup and direct HTTPS download to MediaStore | Build-tested; sample formats not live-validated |
| Uploads | Private `uploadimagews`, queued/retryable, SHA-256 duplicate avoidance | Endpoint acceptance unverified against live Apple; no asset ID/appearance confirmation; unavailable for PCS-required Photos |
| Camera Upload | 30-minute WorkManager scan of `DCIM/Camera`, images and videos, network and charging constraints | Unit state and build checks only; no device/background validation |
| Album write, viewer, Live Photos | Viewer supports pinch zoom, loaded-page swipes, video playback, and paired Live Photo motion playback/download. Album assignment is not implemented. RAW stays downloadable/shareable without preview. | Build-tested; device media codecs and Live Photo resource behavior unverified |

## License decision

iPhotos is MIT-licensed. Its production protocol dependency is rclone, which is MIT-licensed, and its source is not copied into this repository; the compiled Android library includes rclone code. The rclone copyright/license notice is retained in `THIRD_PARTY_NOTICES.md`. icloudgo is an Apache-2.0 research reference only; this project does not copy its implementation. The generated native bridge is open source here. Dependency versions are pinned in `native/icloudbridge/go.mod` and the Gradle version catalog.

## References

- [rclone Apple session source, v1.75.1](https://github.com/rclone/rclone/blob/v1.75.1/backend/iclouddrive/api/session.go)
- [rclone Photos/CloudKit source, v1.75.1](https://github.com/rclone/rclone/blob/v1.75.1/backend/iclouddrive/api/photos.go)
- [rclone Photos API source, v1.75.1](https://github.com/rclone/rclone/blob/v1.75.1/backend/iclouddrive/api/photos.go)
- [pyicloud Photos version lookup and resource fields](https://github.com/picklepete/pyicloud/blob/master/pyicloud/services/photos.py)
- [icloudgo project and upload command](https://github.com/chyroc/icloudgo)
- [Round Sync Android client](https://github.com/newhinton/Round-Sync)
- [Apple Platform Security](https://support.apple.com/guide/security/welcome/web)
