# Third-party notices

Unless a file or dependency is identified below, original iPhotos source code is
Copyright 2026 Simone Esposito and licensed under the Apache License, Version 2.0.
The project license is in [`LICENSE`](LICENSE).

## rclone

iPhotos' compiled `app/libs/icloudbridge.aar` includes the rclone iCloud protocol client from [rclone v1.75.1](https://github.com/rclone/rclone/tree/v1.75.1), distributed under the MIT License. The source dependency is pinned in `native/icloudbridge/go.mod`. The small local extension in [`native/icloudbridge/internal/icloudapi`](native/icloudbridge/internal/icloudapi/README.md) copies and adapts the upstream API sources to expose bounded CloudKit `startRank` pages.

The copied and adapted files in `native/icloudbridge/internal/icloudapi` remain
under the upstream MIT License; this project does not relicense those files. The
MIT copyright and license text from the upstream release is reproduced in
[`licenses/rclone-COPYING`](licenses/rclone-COPYING).

## Go Mobile

The generated Java bindings in `app/libs/icloudbridge-sources.jar` use the Go
Mobile binding generator from `golang.org/x/mobile`. Those generated sources
retain the Go Authors' BSD-style license; its text is in
[`licenses/golang-x-mobile-COPYING`](licenses/golang-x-mobile-COPYING).

## Other libraries

The Android and Go dependency versions are declared in `gradle/libs.versions.toml` and `native/icloudbridge/go.mod`. Go's module checksums are in `native/icloudbridge/go.sum`. Their individual license terms remain with each upstream dependency. The generated AAR includes the Go packages linked into the native library.

icloudgo is a research reference for the undocumented Photos upload endpoint only. iPhotos does not copy its Apache-2.0 source code.
