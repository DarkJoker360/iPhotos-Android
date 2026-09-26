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

// Package icloudbridge is the small gomobile-facing surface used by iPhotos.
// Apple protocol and Photos CloudKit behavior use rclone's maintained client,
// with iPhotos' bounded startRank page extension in internal/icloudapi.
package icloudbridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/lib/pacer"
	api "ocloud/icloudbridge/internal/icloudapi"
)

const clientID = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"

type Bridge struct {
	mu        sync.Mutex
	client    *api.Client
	photos    *api.PhotosService
	appleID   string
	cacheDir  string
	albumRefs map[string]albumRef
}

type albumRef struct {
	Library string
	Album   *api.Album
	Folder  bool
	Path    string
	Count   int
}

type albumDTO struct {
	ID                     string `json:"id"`
	Title                  string `json:"title"`
	AssetCount             int    `json:"assetCount"`
	LibraryID              string `json:"libraryId"`
	Folder                 bool   `json:"folder"`
	RequiresAuthentication bool   `json:"requiresAuthentication,omitempty"`
}

type photoDTO struct {
	ID                    string   `json:"id"`
	Title                 string   `json:"title"`
	CapturedAt            int64    `json:"capturedAtMillis"`
	Size                  int64    `json:"sizeBytes"`
	Width                 int      `json:"width"`
	Height                int      `json:"height"`
	Orientation           *int     `json:"orientation,omitempty"`
	DurationMillis        int64    `json:"durationMillis,omitempty"`
	Favorite              bool     `json:"favorite"`
	AlbumIDs              []string `json:"albumIds"`
	ResourceKey           string   `json:"resourceKey"`
	ThumbnailURL          string   `json:"thumbnailUrl,omitempty"`
	LiveMotionResourceKey string   `json:"liveMotionResourceKey,omitempty"`
	MediaKind             string   `json:"mediaKind"`
	LibraryID             string   `json:"libraryId"`
	CloudRecordID         string   `json:"cloudRecordId"`
}

// NewBridge creates a bridge. cacheDir is app-private and contains protocol
// metadata caches only; session secrets are returned to Kotlin for Keystore
// encryption instead of being persisted by this package.
func NewBridge(cacheDir string) *Bridge {
	if cacheDir != "" {
		_ = os.MkdirAll(cacheDir, 0700)
		_ = os.Setenv("XDG_CACHE_HOME", cacheDir)
	}
	return &Bridge{cacheDir: cacheDir, albumRefs: make(map[string]albumRef)}
}

// BeginAuthentication performs Apple's SRP sign-in and requests a trusted
// device push when Apple says the account requires 2FA. The password is used
// only for this call; the client is reconstructed with an empty password
// before this method returns.
func (b *Bridge) BeginAuthentication(appleID, password string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if strings.TrimSpace(appleID) == "" || password == "" {
		return "", errors.New("Apple Account email and password are required")
	}
	b.appleID = strings.TrimSpace(appleID)
	client, err := api.New(b.appleID, password, "", clientID, nil, nil, "ocloud", "")
	if err != nil {
		return "", safeError(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	err = client.Authenticate(ctx)
	if err != nil {
		return "", safeError(err)
	}
	if client.Session.Requires2FA() {
		// Apple 26.4+ no longer always sends a push automatically. A failed
		// push request does not prevent the SMS fallback from being offered.
		_ = client.Session.RequestPushNotification(ctx)
		b.client, err = b.cloneWithoutPassword(client, "")
		if err != nil {
			return "", safeError(err)
		}
		return "requires_two_factor", nil
	}
	b.client, err = b.cloneWithoutPassword(client, api.WsPhotos)
	if err != nil {
		return "", safeError(err)
	}
	b.photos = nil
	return "signed_in", nil
}

// RestoreSession rehydrates session JSON decrypted by Android Keystore.
func (b *Bridge) RestoreSession(appleID, sessionJSON string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if strings.TrimSpace(appleID) == "" || sessionJSON == "" {
		return errors.New("saved session is incomplete; sign in again")
	}
	client, err := b.newEmptyClient(strings.TrimSpace(appleID), api.WsPhotos)
	if err != nil {
		return safeError(err)
	}
	if err := json.Unmarshal([]byte(sessionJSON), client.Session); err != nil {
		return errors.New("saved session could not be read; sign in again")
	}
	if client.Session.ClientID == "" {
		client.Session.ClientID = clientID
	}
	b.appleID = strings.TrimSpace(appleID)
	b.client = client
	b.photos = nil
	b.albumRefs = make(map[string]albumRef)
	return nil
}

// ExportSession returns the serialized Apple session for encrypted storage by
// Kotlin. Call it only after each authentication or service operation.
func (b *Bridge) ExportSession() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil || b.client.Session == nil {
		return "", errors.New("there is no authenticated session")
	}
	data, err := json.Marshal(b.client.Session)
	if err != nil {
		return "", errors.New("could not serialize Apple session")
	}
	return string(data), nil
}

// RequestPushApproval explicitly requests an Apple trusted-device prompt.
func (b *Bridge) RequestPushApproval() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return errors.New("sign in first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if err := b.client.Session.RequestPushNotification(ctx); err != nil {
		return safeError(err)
	}
	return nil
}

// TrustedPhones returns only the phone IDs, display values and delivery modes
// supplied by Apple so the UI can offer Apple's own SMS fallback.
func (b *Bridge) TrustedPhones() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return "", errors.New("sign in first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	state, err := b.client.Session.GetAuthState(ctx)
	if err != nil {
		return "", safeError(err)
	}
	data, err := json.Marshal(state.TrustedPhoneNumbers)
	return string(data), err
}

// RequestSMSCode asks Apple to send a verification code to the selected phone.
func (b *Bridge) RequestSMSCode(phoneID int, mode string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return errors.New("sign in first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if err := b.client.Session.RequestSMSCode(ctx, phoneID, mode); err != nil {
		return safeError(err)
	}
	return nil
}

// VerifyTwoFactor validates a push-based 2FA code. rclone's session API also
// performs Apple's trust-session and account-login steps.
func (b *Bridge) VerifyTwoFactor(code string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return errors.New("sign in first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := b.client.Session.Validate2FACode(ctx, strings.TrimSpace(code)); err != nil {
		return safeError(err)
	}
	client, err := b.cloneWithoutPassword(b.client, api.WsPhotos)
	if err != nil {
		return safeError(err)
	}
	b.client = client
	b.photos = nil
	return nil
}

// VerifySMSCode validates an Apple SMS code for the selected trusted number.
func (b *Bridge) VerifySMSCode(code string, phoneID int, mode string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return errors.New("sign in first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := b.client.Session.ValidateSMSCode(ctx, strings.TrimSpace(code), phoneID, mode); err != nil {
		return safeError(err)
	}
	client, err := b.cloneWithoutPassword(b.client, api.WsPhotos)
	if err != nil {
		return safeError(err)
	}
	b.client = client
	b.photos = nil
	return nil
}

// EstablishPhotosAccess authenticates the Photos web service and obtains
// service-specific PCS cookies when Apple requires trusted-device approval.
func (b *Bridge) EstablishPhotosAccess() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return errors.New("sign in first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Minute)
	defer cancel()
	if err := b.client.Authenticate(ctx); err != nil {
		return safeError(err)
	}
	b.photos = nil
	return nil
}

// PhotosPCSRequired reports the Photos service's pcsRequired service flag.
// This is not treated as proof that ADP is enabled; it means Apple requires
// service-specific PCS authorization for this Photos session.
func (b *Bridge) PhotosPCSRequired() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil || b.client.Session == nil || b.client.Session.AccountInfo.Webservices == nil {
		return false
	}
	service := b.client.Session.AccountInfo.Webservices[api.WsPhotos]
	return service != nil && service.PcsRequired
}

// Albums returns Apple library, built-in smart albums, and user albums.
func (b *Bridge) Albums() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	photos, err := b.photosService(ctx)
	if err != nil {
		return "", safeError(err)
	}
	libraries, err := photos.GetLibraries(ctx)
	if err != nil {
		return "", safeError(err)
	}
	result := make([]albumDTO, 0)
	refs := make(map[string]albumRef)
	for libraryID, library := range libraries {
		albums, err := library.GetAlbums(ctx)
		if err != nil {
			return "", safeError(err)
		}
		counts, _ := library.GetAlbumCounts(ctx)
		for name, album := range albums {
			collectAlbums(libraryID, "", name, album, counts, &result, refs)
		}
	}
	sort.Slice(result, func(i, j int) bool { return strings.ToLower(result[i].Title) < strings.ToLower(result[j].Title) })
	b.albumRefs = refs
	data, err := json.Marshal(result)
	return string(data), err
}

// PhotosPage returns one CloudKit startRank page without materializing the
// rest of the album. Cursors are asset offsets and results are newest first.
func (b *Bridge) PhotosPage(albumID, cursor string, pageSize int) (string, error) {
	if pageSize < 1 {
		pageSize = 60
	}
	if pageSize > 100 {
		pageSize = 100
	}
	b.mu.Lock()
	ref, ok := b.albumRefs[albumID]
	if !ok {
		// Album handles are rebuilt as one locked snapshot by Albums. Retry once
		// when a page races a session/cache refresh instead of surfacing a
		// transient "album unavailable" error to the gallery.
		b.mu.Unlock()
		if _, err := b.Albums(); err != nil {
			return "", safeError(err)
		}
		b.mu.Lock()
		ref, ok = b.albumRefs[albumID]
		if !ok {
			b.mu.Unlock()
			return "", errors.New("this album is no longer available; refresh the album list")
		}
	}
	defer b.mu.Unlock()
	if ref.Folder {
		return "", errors.New("folders do not contain photos directly")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	_, err := b.photosService(ctx)
	if err != nil {
		return "", safeError(err)
	}
	offset, err := strconv.Atoi(cursor)
	if err != nil || offset < 0 {
		offset = 0
	}
	// CloudKit's startRank is an offset in the requested direction. Pairing a
	// descending query with count-1-offset starts at the oldest end and makes a
	// large library look as if it stops years ago. Rank from zero in descending
	// order so the gallery opens at the newest captures.
	startRank, direction := newestFirstRank(offset)
	ref.Album.Direction = direction
	photosInPage, consumed, pageHasMore, err := ref.Album.GetPhotosPage(ctx, startRank, pageSize)
	if err != nil {
		return "", safeError(err)
	}
	motion := make(map[string]bool)
	for _, photo := range photosInPage {
		if photo.ResourceKey == "resOriginalVidComplRes" {
			motion[photo.ID] = true
		}
	}
	items := make([]photoDTO, 0, len(photosInPage))
	for _, photo := range photosInPage {
		if photo.ResourceKey == "resOriginalVidComplRes" {
			continue
		}
		kind := mediaKind(photo.Filename)
		motionKey := ""
		if motion[photo.ID] && photo.ResourceKey == "resOriginalRes" {
			kind = "LivePhoto"
			motionKey = "resOriginalVidComplRes"
		}
		var orientation *int
		if photo.HasOrientation {
			value := photo.Orientation
			orientation = &value
		}
		items = append(items, photoDTO{
			ID: assetID(ref.Library, photo.ID, photo.ResourceKey), Title: photo.Filename,
			CapturedAt: photo.AssetDate, Size: photo.Size, Width: photo.Width,
			Height:      photo.Height,
			Orientation: orientation, DurationMillis: int64(photo.DurationSeconds * 1000),
			Favorite: photo.IsFavorite,
			AlbumIDs: []string{albumID}, ResourceKey: photo.ResourceKey, LiveMotionResourceKey: motionKey,
			ThumbnailURL: photo.ThumbnailURL,
			MediaKind:    kind, LibraryID: ref.Library, CloudRecordID: photo.ID,
		})
	}
	next := nextPhotoCursor(offset, consumed, ref.Count, pageHasMore)
	return encodePhotoPage(items, next)
}

func newestFirstRank(offset int) (int, string) {
	return offset, "DESCENDING"
}

func nextPhotoCursor(offset, consumed, count int, hasMore bool) string {
	if offset < 0 || consumed <= 0 {
		return ""
	}
	nextOffset := offset + consumed
	if count > 0 {
		if nextOffset >= count {
			return ""
		}
		// CloudKit's indexed rank query may return one master without a
		// continuation marker. The library count is the pagination bound.
		return strconv.Itoa(nextOffset)
	}
	if hasMore {
		return strconv.Itoa(nextOffset)
	}
	return ""
}

func encodePhotoPage(items []photoDTO, next string) (string, error) {
	data, err := json.Marshal(struct {
		Assets     []photoDTO `json:"assets"`
		NextCursor string     `json:"nextCursor"`
	}{items, next})
	return string(data), err
}

// RefreshPhotoCache drops the bridge's album handles so the next request
// refreshes album metadata and checks rclone's CloudKit sync-token cache.
func (b *Bridge) RefreshPhotoCache() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.albumRefs = make(map[string]albumRef)
}

// Download writes an original resource to destination without buffering the
// photo in JVM memory. The path must be app-private or a caller-owned cache path.
func (b *Bridge) Download(assetID, resourceKey, destination string) error {
	library, recordID, encodedResourceKey, err := parseAssetID(assetID)
	if err != nil {
		return err
	}
	if resourceKey == "" {
		resourceKey = encodedResourceKey
	}
	timeout := 10 * time.Minute
	if resourceKey == "resJPEGThumbRes" || resourceKey == "resJPEGMedRes" {
		timeout = 45 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	// Hold the bridge lock only while reading shared session state and asking
	// CloudKit for a short-lived URL. The CDN transfer can take minutes for
	// originals; keeping the lock during it blocked every thumbnail and page
	// request behind that download.
	downloadURL, err := func() (string, error) {
		b.mu.Lock()
		defer b.mu.Unlock()
		photos, photosErr := b.photosService(ctx)
		if photosErr != nil {
			return "", photosErr
		}
		return photos.LookupDownloadURL(ctx, recordID, library, resourceKey)
	}()
	if err != nil {
		return safeError(err)
	}
	return downloadToPath(ctx, downloadURL, destination)
}

// ResolveThumbnailURLs batches fresh lookups for cached gallery assets. URLs
// are short-lived and should stay in memory; callers must not persist them.
func (b *Bridge) ResolveThumbnailURLs(assetIDsJSON string) (string, error) {
	var assetIDs []string
	if err := json.Unmarshal([]byte(assetIDsJSON), &assetIDs); err != nil || len(assetIDs) > 100 {
		return "", errors.New("invalid thumbnail lookup request")
	}
	if len(assetIDs) == 0 {
		return "{}", nil
	}
	type target struct {
		assetID  string
		recordID string
	}
	byLibrary := make(map[string][]target)
	for _, id := range assetIDs {
		library, record, _, err := parseAssetID(id)
		if err != nil {
			return "", err
		}
		byLibrary[library] = append(byLibrary[library], target{assetID: id, recordID: record})
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	photos, err := b.photosService(ctx)
	if err != nil {
		return "", safeError(err)
	}
	resolved := make(map[string]string, len(assetIDs))
	for library, targets := range byLibrary {
		pending := append([]target(nil), targets...)
		for _, resourceKey := range []string{"resJPEGThumbRes", "resJPEGMedRes"} {
			if len(pending) == 0 {
				break
			}
			recordIDs := make([]string, len(pending))
			for i, target := range pending {
				recordIDs[i] = target.recordID
			}
			urls, lookupErr := photos.LookupDownloadURLs(ctx, recordIDs, library, resourceKey)
			if lookupErr != nil {
				return "", safeError(lookupErr)
			}
			remaining := make([]target, 0, len(pending))
			for _, target := range pending {
				if downloadURL := urls[target.recordID]; downloadURL != "" {
					resolved[target.assetID] = downloadURL
				} else {
					remaining = append(remaining, target)
				}
			}
			pending = remaining
		}
	}
	encoded, err := json.Marshal(resolved)
	return string(encoded), err
}

// DownloadURL streams a short-lived Apple CDN URL to an app-private file.
// The URL comes from a listing/lookup response and is not persisted.
func (b *Bridge) DownloadURL(downloadURL, destination string) error {
	parsed, err := url.Parse(downloadURL)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil {
		return errors.New("Apple returned an invalid photo link")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	return downloadToPath(ctx, downloadURL, destination)
}

func downloadToPath(ctx context.Context, downloadURL, destination string) error {
	if err := os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
		return errors.New("could not prepare private download storage")
	}
	tmp := destination + ".part"
	_ = os.Remove(tmp)
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return errors.New("could not create private download file")
	}
	response, err := fetchDownload(ctx, downloadURL)
	if err != nil {
		_ = out.Close()
		_ = os.Remove(tmp)
		return err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		_ = out.Close()
		_ = os.Remove(tmp)
		if response.StatusCode == http.StatusTooManyRequests {
			return errors.New("Apple is temporarily limiting photo requests. Wait a moment, then try again.")
		}
		if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden || response.StatusCode == http.StatusNotFound {
			return errors.New("Apple photo link expired")
		}
		return fmt.Errorf("Apple download failed with HTTP %d", response.StatusCode)
	}
	_, copyErr := io.Copy(out, response.Body)
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(tmp)
		return errors.New("original download was interrupted")
	}
	if err := os.Rename(tmp, destination); err != nil {
		_ = os.Remove(tmp)
		return errors.New("could not finish saving the original")
	}
	return nil
}

// Upload sends a supported asset to Apple's uploadimagews service. The API is
// undocumented and currently exposed by icloudgo; it only returns duplicate
// status, not the resulting Photos record ID. Upload is refused when the Photos
// webservice requires PCS because this upload path has no verified ADP flow.
func (b *Bridge) Upload(filename, sourcePath string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return "", errors.New("sign in first")
	}
	if b.client.Session == nil || b.client.Session.AccountInfo.Webservices == nil {
		return "", errors.New("Apple Photos service is unavailable")
	}
	photosWS := b.client.Session.AccountInfo.Webservices[api.WsPhotos]
	if photosWS == nil {
		return "", errors.New("Apple Photos service is unavailable")
	}
	if photosWS.PcsRequired {
		return "", errors.New("Apple's Photos upload endpoint has no verified PCS flow; upload is unavailable for this account security mode")
	}
	uploadWS, ok := b.client.Session.AccountInfo.Webservices["uploadimagews"]
	if !ok || uploadWS == nil || uploadWS.URL == "" {
		return "", errors.New("Apple did not provide the Photos upload service")
	}
	file, err := os.Open(sourcePath)
	if err != nil {
		return "", errors.New("selected photo could not be opened")
	}
	defer file.Close()
	endpoint, err := url.Parse(strings.TrimRight(uploadWS.URL, "/") + "/upload")
	if err != nil {
		return "", errors.New("Apple provided an invalid Photos upload endpoint")
	}
	query := endpoint.Query()
	query.Set("filename", filepath.Base(filename))
	endpoint.RawQuery = query.Encode()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), file)
	if err != nil {
		return "", errors.New("could not create Apple upload request")
	}
	for key, value := range api.GetCommonHeaders(map[string]string{"Content-Type": "text/plain"}) {
		request.Header.Set(key, value)
	}
	request.Header.Set("Cookie", b.client.Session.GetCookieString())
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return "", errors.New("could not upload the photo to Apple")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("Apple upload failed with HTTP %d", response.StatusCode)
	}
	var result struct {
		IsDuplicate bool `json:"isDuplicate"`
	}
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
		return "", errors.New("Apple upload response could not be read")
	}
	if result.IsDuplicate {
		return "duplicate", nil
	}
	return "accepted", nil
}

// ClearSession forgets credentials from the current bridge instance.
func (b *Bridge) ClearSession() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.client = nil
	b.photos = nil
	b.appleID = ""
	b.albumRefs = make(map[string]albumRef)
}

func (b *Bridge) photosService(ctx context.Context) (*api.PhotosService, error) {
	if b.client == nil {
		return nil, errors.New("sign in first")
	}
	if b.photos != nil {
		return b.photos, nil
	}
	p := fs.NewPacer(ctx, pacer.NewDefault())
	service, err := api.NewPhotosService(ctx, b.client, p, shouldRetry)
	if err != nil {
		return nil, err
	}
	b.photos = service
	return service, nil
}

func (b *Bridge) newEmptyClient(appleID, pcsWSKey string) (*api.Client, error) {
	return api.New(appleID, "", "", clientID, nil, nil, "ocloud", pcsWSKey)
}

func (b *Bridge) cloneWithoutPassword(source *api.Client, pcsWSKey string) (*api.Client, error) {
	data, err := json.Marshal(source.Session)
	if err != nil {
		return nil, errors.New("could not preserve Apple session")
	}
	client, err := b.newEmptyClient(b.appleID, pcsWSKey)
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, client.Session); err != nil {
		return nil, errors.New("could not restore Apple session")
	}
	return client, nil
}

func collectAlbums(libraryID, parentPath, name string, album *api.Album, counts map[string]int64, result *[]albumDTO, refs map[string]albumRef) {
	fullPath := path.Join(parentPath, name)
	id := encodeAlbumID(libraryID, fullPath)
	count := 0
	if n, ok := counts[name]; ok && n > 0 {
		count = int(n)
	}
	requiresAuthentication := name == "Hidden" || name == "Recently Deleted"
	*result = append(*result, albumDTO{
		ID: id, Title: name, AssetCount: count, LibraryID: libraryID,
		Folder: album.IsFolder, RequiresAuthentication: requiresAuthentication,
	})
	refs[id] = albumRef{Library: libraryID, Album: album, Folder: album.IsFolder, Path: fullPath, Count: count}
	if album.IsFolder {
		for childName, child := range album.Children {
			collectAlbums(libraryID, fullPath, childName, child, counts, result, refs)
		}
	}
}

func encodeAlbumID(libraryID, name string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(libraryID + "\x00" + name))
}

func assetID(libraryID, recordID, resourceKey string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(libraryID + "\x00" + recordID + "\x00" + resourceKey))
}

func parseAssetID(value string) (string, string, string, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return "", "", "", errors.New("asset ID is invalid")
	}
	parts := strings.SplitN(string(decoded), "\x00", 3)
	if len(parts) < 2 || parts[0] == "" || parts[1] == "" {
		return "", "", "", errors.New("asset ID is invalid")
	}
	resourceKey := "resOriginalRes"
	if len(parts) == 3 && parts[2] != "" {
		resourceKey = parts[2]
	}
	return parts[0], parts[1], resourceKey, nil
}

func mediaKind(filename string) string {
	ext := strings.ToLower(filepath.Ext(filename))
	if strings.HasPrefix(strings.ToLower(filename), "live photo") {
		return "LivePhoto"
	}
	switch ext {
	case ".mp4", ".mov", ".m4v", ".avi", ".m2v":
		return "Video"
	case ".dng", ".cr2", ".cr3", ".nef", ".arw", ".raf", ".rw2", ".orf", ".pef", ".nrw", ".crw":
		return "Raw"
	default:
		return "Photo"
	}
}

func shouldRetry(_ context.Context, response *http.Response, err error) (bool, error) {
	if response == nil {
		return false, err
	}
	if response.StatusCode == http.StatusTooManyRequests || response.StatusCode >= 500 {
		if retryAfter := retryAfterDelay(response.Header.Get("Retry-After"), time.Now()); retryAfter > 0 {
			if retryAfter > 30*time.Second {
				return false, errors.New("Apple is temporarily limiting photo requests. Wait a moment, then retry.")
			}
			return true, pacer.RetryAfterError(err, retryAfter)
		}
		return true, err
	}
	return false, err
}

func retryAfterDelay(value string, now time.Time) time.Duration {
	value = strings.TrimSpace(value)
	if seconds, err := strconv.Atoi(value); err == nil && seconds >= 0 {
		return time.Duration(seconds) * time.Second
	}
	if retryAt, err := http.ParseTime(value); err == nil {
		return retryAt.Sub(now).Truncate(time.Second)
	}
	return 0
}

func fetchDownload(ctx context.Context, downloadURL string) (*http.Response, error) {
	client := &http.Client{Timeout: 10 * time.Minute}
	for attempt := 0; attempt < 4; attempt++ {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
		if err != nil {
			return nil, errors.New("Apple returned an invalid download URL")
		}
		response, err := client.Do(request)
		if err != nil {
			return nil, errors.New("could not download the original from Apple")
		}
		if response.StatusCode != http.StatusTooManyRequests && response.StatusCode < 500 {
			return response, nil
		}
		wait := retryAfterDelay(response.Header.Get("Retry-After"), time.Now())
		if wait <= 0 {
			wait = time.Duration(1<<attempt) * 250 * time.Millisecond
		}
		if attempt == 3 || wait > 30*time.Second {
			return response, nil
		}
		_ = response.Body.Close()
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, errors.New("Apple photo download timed out")
		case <-timer.C:
		}
	}
	return nil, errors.New("could not download the original from Apple")
}

func safeError(err error) error {
	if err == nil {
		return nil
	}
	message := strings.ToLower(err.Error())
	switch {
	case strings.Contains(message, "429"), strings.Contains(message, "too many requests"), strings.Contains(message, "temporarily limiting"):
		return errors.New("Apple is temporarily limiting photo requests. Wait a moment, then try again.")
	case strings.Contains(message, "incorrect username or password"):
		return errors.New("Apple rejected sign-in. Check the Apple Account and password, then try again.")
	case strings.Contains(message, "timed out waiting for device approval"):
		return errors.New("Apple Photos approval timed out. Approve access on a trusted Apple device and try again.")
	case strings.Contains(message, "pcs") || strings.Contains(message, "423"):
		return errors.New("Apple could not authorize iCloud Photos access. Check that web access to iCloud data is enabled and approve any trusted-device prompt.")
	case strings.Contains(message, "trust token expired"), strings.Contains(message, "session expired"), strings.Contains(message, "http error 401"), strings.Contains(message, "http error 421"):
		return errors.New("Your Apple session expired. Sign in again and complete two-factor authentication.")
	case strings.Contains(message, "http error 403"):
		return errors.New("Apple denied access to iCloud Photos. Confirm trusted-device approval and web access to iCloud data, then try again.")
	default:
		return errors.New("Apple iCloud Photos request failed. Check your connection and try again.")
	}
}
