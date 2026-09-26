#!/usr/bin/env sh
# Copyright 2026 Simone Esposito
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
MOBILE_VERSION=v0.0.0-20260908204917-8b95e45f8d3e
ANDROID_API=${OCLOUD_ANDROID_API:-33}

command -v go >/dev/null 2>&1 || { echo "Go 1.26 is required" >&2; exit 1; }
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
GO_BIN_DIR="$(go env GOPATH)/bin"
export PATH="$GO_BIN_DIR:$PATH"

# gomobile still defaults to Android API 16, but current NDK releases start at
# API 21. Use the installed SDK's NDK explicitly and target the app's minSdk.
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$SDK_DIR" ] && [ -f "$ROOT_DIR/local.properties" ]; then
    SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | sed 's/\\:/\:/g')
fi
if [ -n "$SDK_DIR" ] && [ -z "${ANDROID_HOME:-}" ]; then
    export ANDROID_HOME="$SDK_DIR"
fi
if [ -n "$SDK_DIR" ] && [ -z "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR=$(find "$SDK_DIR/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -Vr | head -n 1 || true)
    if [ -n "$NDK_DIR" ]; then
        export ANDROID_NDK_HOME="$NDK_DIR"
    fi
fi

# `gomobile init` hardcodes API 16 while current NDKs start at API 21. It only
# installs gobind and creates this marker directory, both handled above.
mkdir -p "$(go env GOPATH)/pkg/gomobile"

cd "$ROOT_DIR/native/icloudbridge"
gomobile bind \
    -androidapi "$ANDROID_API" \
    -target android/arm,android/arm64 \
    -ldflags="-s -w" \
    -javapkg icloudbridge \
    -o "$ROOT_DIR/app/libs/icloudbridge.aar" \
    .
