/*
 * Copyright 2026 Simone Esposito
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package icloudbridge

import (
	"encoding/base64"
	"testing"

	api "ocloud/icloudbridge/internal/icloudapi"
)

func TestAssetIDsIncludeResourceKey(t *testing.T) {
	original := assetID("PrimarySync", "record-123", "resOriginalRes")
	liveMotion := assetID("PrimarySync", "record-123", "resOriginalVidComplRes")
	if original == liveMotion {
		t.Fatal("different resources for the same CloudKit record must have distinct local IDs")
	}

	library, record, resource, err := parseAssetID(liveMotion)
	if err != nil {
		t.Fatal(err)
	}
	if library != "PrimarySync" || record != "record-123" || resource != "resOriginalVidComplRes" {
		t.Fatalf("unexpected asset ID parts: %q %q %q", library, record, resource)
	}
}

func TestParseLegacyAssetIDDefaultsToOriginalResource(t *testing.T) {
	value := encodeLegacyAssetID("PrimarySync", "record-legacy")
	library, record, resource, err := parseAssetID(value)
	if err != nil {
		t.Fatal(err)
	}
	if library != "PrimarySync" || record != "record-legacy" || resource != "resOriginalRes" {
		t.Fatalf("unexpected legacy ID parts: %q %q %q", library, record, resource)
	}
}

func TestParseAssetIDRejectsMalformedInput(t *testing.T) {
	for _, value := range []string{"!not-base64!", "", "AAAA"} {
		if _, _, _, err := parseAssetID(value); err == nil {
			t.Errorf("parseAssetID(%q) unexpectedly succeeded", value)
		}
	}
}

func TestPhotosPageWalksNewestToOldestByOffset(t *testing.T) {
	for _, offset := range []int{0, 100, 1200} {
		rank, direction := newestFirstRank(offset)
		if rank != offset || direction != "DESCENDING" {
			t.Fatalf("offset %d produced rank=%d direction=%q", offset, rank, direction)
		}
	}
	if nextPhotoCursor(0, 1, 53000, false) != "1" {
		t.Fatal("count-bounded page cursor did not advance when CloudKit omitted a continuation marker")
	}
	if nextPhotoCursor(100, 0, 53000, true) != "" || nextPhotoCursor(52990, 10, 53000, false) != "" {
		t.Fatal("page cursor advanced despite an empty or exhausted page")
	}
	if nextPhotoCursor(100, 20, 0, true) != "120" || nextPhotoCursor(100, 20, 0, false) != "" {
		t.Fatal("unknown-count page cursor did not respect CloudKit's continuation state")
	}
}

func TestProtectedSmartAlbumsRequireDeviceAuthentication(t *testing.T) {
	for _, test := range []struct {
		name      string
		protected bool
	}{
		{name: "Hidden", protected: true},
		{name: "Recently Deleted", protected: true},
		{name: "All Photos", protected: false},
	} {
		var albums []albumDTO
		collectAlbums("PrimarySync", "", test.name, api.SmartAlbums[test.name], nil, &albums, make(map[string]albumRef))
		if len(albums) != 1 || albums[0].RequiresAuthentication != test.protected {
			t.Errorf("album %q requiresAuthentication=%v, want %v", test.name, albums[0].RequiresAuthentication, test.protected)
		}
	}
}

func encodeLegacyAssetID(libraryID, recordID string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(libraryID + "\x00" + recordID))
}
