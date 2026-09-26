/*
 * Copyright (C) 2012 by Nick Craig-Wood http://www.craig-wood.com/nick/
 * Modifications Copyright 2026 Simone Esposito
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * The MIT License text is also provided in licenses/rclone-COPYING.
 */

package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/rclone/rclone/lib/rest"
)

func TestGetPhotosPageUsesBoundedDescendingStartRank(t *testing.T) {
	t.Setenv("XDG_CACHE_HOME", t.TempDir())
	var request map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		decoder := json.NewDecoder(r.Body)
		decoder.UseNumber()
		if err := decoder.Decode(&request); err != nil {
			t.Fatalf("decode query: %v", err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"records":[{"recordName":"asset-1","recordType":"CPLAsset","fields":{"masterRef":{"value":{"recordName":"master-1"}},"assetDate":{"value":100},"orientation":{"value":6},"duration":{"value":12.5}}},{"recordName":"master-1","recordType":"CPLMaster","fields":{"filenameEnc":{"value":"photo.jpg","type":"STRING"},"resOriginalRes":{"value":{"size":12,"downloadURL":"https://example.invalid/photo"}},"resJPEGThumbRes":{"value":{"size":4,"downloadURL":"https://example.invalid/thumb"}}}}],"syncToken":"token-1"}`))
	}))
	defer server.Close()

	service := NewTestPhotosService(map[string]map[string]*Album{
		"PrimarySync": {
			"All Photos": {Name: "All Photos", ListType: "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted", Direction: "DESCENDING"},
		},
	})
	service.endpoint = server.URL
	service.client.Session.srv = rest.NewClient(server.Client())
	album := service.libraries["PrimarySync"].albums["All Photos"]
	photos, consumed, more, err := album.GetPhotosPage(context.Background(), 900, 100)
	if err != nil {
		t.Fatalf("GetPhotosPage: %v", err)
	}
	if consumed != 1 || more || len(photos) != 1 || photos[0].Filename != "photo.jpg" {
		t.Fatalf("unexpected page: consumed=%d more=%v photos=%+v", consumed, more, photos)
	}
	if !photos[0].HasOrientation || photos[0].Orientation != 6 || photos[0].DurationSeconds != 12.5 {
		t.Fatalf("photo metadata was not retained: %+v", photos[0])
	}
	if photos[0].ThumbnailURL != "https://example.invalid/thumb" {
		t.Fatalf("thumbnail URL was not retained from page: %q", photos[0].ThumbnailURL)
	}
	query, ok := request["query"].(map[string]any)
	if !ok {
		t.Fatalf("request has no query: %#v", request)
	}
	filters, ok := query["filterBy"].([]any)
	if !ok || len(filters) < 2 {
		t.Fatalf("request has no rank/direction filters: %#v", query)
	}
	var startRank, direction string
	for _, raw := range filters {
		filter, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		switch filter["fieldName"] {
		case "startRank":
			value := filter["fieldValue"].(map[string]any)
			startRank = value["value"].(json.Number).String()
		case "direction":
			value := filter["fieldValue"].(map[string]any)
			direction = value["value"].(string)
		}
	}
	if startRank != "900" || direction != "DESCENDING" {
		t.Fatalf("wrong rank query: startRank=%q direction=%q", startRank, direction)
	}
	if request["resultsLimit"] != json.Number("200") {
		t.Fatalf("requested more than one bounded page: limit=%#v", request["resultsLimit"])
	}
}

func TestLookupDownloadURLsBatchesAndMapsByRecordName(t *testing.T) {
	t.Setenv("XDG_CACHE_HOME", t.TempDir())
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		var query struct {
			Records []struct {
				RecordName string `json:"recordName"`
			} `json:"records"`
		}
		if err := json.NewDecoder(r.Body).Decode(&query); err != nil {
			t.Fatalf("decode lookup: %v", err)
		}
		if len(query.Records) != 2 || query.Records[0].RecordName != "master-1" || query.Records[1].RecordName != "master-2" {
			t.Fatalf("expected both records in one lookup, got %#v", query.Records)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"records":[{"recordName":"master-2","fields":{"resJPEGThumbRes":{"value":{"downloadURL":"https://cdn.invalid/two"}}}},{"recordName":"master-1","fields":{"resJPEGThumbRes":{"value":{"downloadURL":"https://cdn.invalid/one"}}}}]}`))
	}))
	defer server.Close()

	service := NewTestPhotosService(map[string]map[string]*Album{
		"PrimarySync": {"All Photos": {Name: "All Photos"}},
	})
	service.endpoint = server.URL
	service.client.Session.srv = rest.NewClient(server.Client())
	urls, err := service.LookupDownloadURLs(context.Background(), []string{"master-1", "master-2"}, "PrimarySync", "resJPEGThumbRes")
	if err != nil {
		t.Fatalf("LookupDownloadURLs: %v", err)
	}
	if requests != 1 || urls["master-1"] != "https://cdn.invalid/one" || urls["master-2"] != "https://cdn.invalid/two" {
		t.Fatalf("unexpected batched URLs: requests=%d urls=%v", requests, urls)
	}
}
