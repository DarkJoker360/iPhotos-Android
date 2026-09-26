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

package xyz.simoneesposito.ocloud.ui

import android.net.Uri
import android.util.LruCache
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import xyz.simoneesposito.ocloud.OCloudApplication
import xyz.simoneesposito.ocloud.R
import xyz.simoneesposito.ocloud.data.database.DownloadTaskEntity
import xyz.simoneesposito.ocloud.data.cache.ThumbnailDiskCache
import xyz.simoneesposito.ocloud.data.download.DownloadQueue
import xyz.simoneesposito.ocloud.data.repository.LibraryRepository
import xyz.simoneesposito.ocloud.data.repository.PhotoDownloadRepository
import xyz.simoneesposito.ocloud.data.sync.LibrarySyncScheduler
import xyz.simoneesposito.ocloud.domain.auth.TrustedPhone
import xyz.simoneesposito.ocloud.domain.auth.AuthState
import xyz.simoneesposito.ocloud.domain.model.PhotoAlbum
import xyz.simoneesposito.ocloud.domain.model.requiresDeviceAuthentication
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import androidx.core.net.toUri
import androidx.core.content.edit

private fun byteCache(maxBytes: Int): LruCache<String, ByteArray> =
    object : LruCache<String, ByteArray>(maxBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

enum class AppTab(@param:StringRes val labelRes: Int) {
    Photos(R.string.tab_photos),
    Collections(R.string.tab_collections),
    Downloads(R.string.tab_downloads),
    Settings(R.string.tab_more),
}

data class OCloudUiState(
    val authState: AuthState = AuthState.LoggedOut,
    val authMessage: String? = null,
    val photos: List<PhotoAsset> = emptyList(),
    val albums: List<PhotoAlbum> = emptyList(),
    val albumCoverAssets: Map<String, PhotoAsset> = emptyMap(),
    val downloadTasks: List<DownloadTaskEntity> = emptyList(),
    val selectedAlbum: PhotoAlbum? = null,
    val selectedPhoto: PhotoAsset? = null,
    val videoPreviewUri: String? = null,
    val videoPreviewAssetId: String? = null,
    val preparingVideoPreview: Boolean = false,
    val tab: AppTab = AppTab.Photos,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val pageError: String? = null,
    val syncing: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val isRestoringSession: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val hasMore: Boolean = true,
    val downloadInProgress: String? = null,
    val notice: String? = null,
)

class OCloudViewModel(private val app: OCloudApplication) : ViewModel() {
    private val library = LibraryRepository(app.database, app.photosProvider)
    private val downloads = PhotoDownloadRepository(app, app.photosProvider, library)
    private val downloadQueue = DownloadQueue(app)
    private val mutableState = MutableStateFlow(OCloudUiState())
    val state = mutableState.asStateFlow()
    private val thumbnailCache = byteCache(maxBytes = 16 * 1024 * 1024)
    private val previewCache = byteCache(maxBytes = 32 * 1024 * 1024)
    private val thumbnailDiskCache = ThumbnailDiskCache(app)
    private val thumbnailRequests = Semaphore(2)
    private val albumCoverRequests = Semaphore(2)
    private val albumCoverRequestsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val thumbnailWarmups = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var authWasReady = false
    private var albumLoadInProgress = false
    @Volatile private var activeAlbum: PhotoAlbum? = null
    private var reloadPublicPhotosOnForeground = false

    init {
        val preferences = app.getSharedPreferences(LibrarySyncScheduler.PREFERENCES, 0)
        mutableState.value = mutableState.value.copy(
            autoSyncEnabled = preferences.getBoolean(LibrarySyncScheduler.KEY_AUTO_SYNC_ENABLED, true),
            isRestoringSession = true,
            hasCompletedOnboarding = preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false),
        )
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(app.authManager.state, app.authManager.message) { auth, message -> auth to message }
                .collectLatest { (auth, message) ->
                    mutableState.value = mutableState.value.copy(authState = auth, authMessage = message)
                    val nowReady = auth is AuthState.Authenticated
                    if (nowReady && !authWasReady) {
                        authWasReady = true
                        thumbnailCache.evictAll()
                        previewCache.evictAll()
                        loadFirstPage()
                        loadAlbums()
                        downloadQueue.retryPending()
                    } else if (!nowReady) {
                        authWasReady = false
                    }
                }
        }
        viewModelScope.launch {
            app.database.downloadTaskDao().observeAll().collectLatest { tasks ->
                val previous = mutableState.value.downloadTasks
                mutableState.value = mutableState.value.copy(downloadTasks = tasks)
                if (mutableState.value.tab == AppTab.Downloads && tasks.any { task ->
                    task.status == "succeeded" && previous.firstOrNull { it.taskId == task.taskId }?.status != "succeeded"
                }) loadDownloads()
            }
        }
        viewModelScope.launch {
            try {
                app.authManager.restoreSession()
            } finally {
                mutableState.value = mutableState.value.copy(isRestoringSession = false)
            }
        }
    }

    fun completeOnboarding() {
        app.getSharedPreferences(LibrarySyncScheduler.PREFERENCES, 0)
            .edit {
                putBoolean(KEY_ONBOARDING_COMPLETE, true)
            }
        mutableState.value = mutableState.value.copy(hasCompletedOnboarding = true)
    }

    fun signIn(appleId: String, password: String) {
        viewModelScope.launch { app.authManager.signIn(appleId, password) }
    }

    fun verifyCode(code: String, phone: TrustedPhone? = null) {
        viewModelScope.launch { app.authManager.verifyCode(code, phone) }
    }

    fun requestSms(phone: TrustedPhone) {
        viewModelScope.launch { app.authManager.requestSms(phone) }
    }

    fun retryDownloads() {
        viewModelScope.launch {
            val count = downloadQueue.retryPending()
            mutableState.value = mutableState.value.copy(
                notice = if (count > 0) app.resources.getQuantityString(R.plurals.downloads_queued_to_retry, count, count)
                else app.getString(R.string.no_failed_downloads),
            )
        }
    }

    fun retryPhotosApproval() {
        viewModelScope.launch { app.authManager.retryPhotosApproval() }
    }

    fun logout() {
        viewModelScope.launch {
            app.authManager.logout()
            thumbnailCache.evictAll()
            previewCache.evictAll()
            thumbnailDiskCache.clear()
            activeAlbum = null
            mutableState.value = OCloudUiState(
                tab = AppTab.Photos,
                hasCompletedOnboarding = mutableState.value.hasCompletedOnboarding,
            )
        }
    }

    fun selectTab(tab: AppTab) {
        val leavingProtectedAlbum = activeAlbum?.requiresDeviceAuthentication() == true
        if (leavingProtectedAlbum) clearProtectedAlbumContent()
        activeAlbum = null
        mutableState.value = mutableState.value.copy(
            tab = tab,
            selectedAlbum = null,
            selectedPhoto = if (leavingProtectedAlbum) null else mutableState.value.selectedPhoto,
            photos = if (leavingProtectedAlbum) emptyList() else mutableState.value.photos,
            loading = if (leavingProtectedAlbum) false else mutableState.value.loading,
            loadingMore = false,
            hasMore = if (leavingProtectedAlbum) true else mutableState.value.hasMore,
            videoPreviewUri = if (leavingProtectedAlbum) null else mutableState.value.videoPreviewUri,
            videoPreviewAssetId = if (leavingProtectedAlbum) null else mutableState.value.videoPreviewAssetId,
            preparingVideoPreview = if (leavingProtectedAlbum) false else mutableState.value.preparingVideoPreview,
            notice = null,
        )
        when (tab) {
            AppTab.Collections -> loadAlbums()
            AppTab.Photos -> if (mutableState.value.photos.isEmpty()) viewModelScope.launch { loadFirstPage() }
            AppTab.Downloads -> loadDownloads()
            else -> Unit
        }
    }

    fun openAlbum(album: PhotoAlbum) {
        if (album.requiresDeviceAuthentication()) {
            showNotice(app.getString(R.string.private_authenticate_before_open))
            return
        }
        openAlbumContent(album)
    }

    fun openAlbumAfterAuthentication(album: PhotoAlbum) {
        if (!album.requiresDeviceAuthentication()) {
            openAlbum(album)
            return
        }
        openAlbumContent(album)
    }

    private fun openAlbumContent(album: PhotoAlbum) {
        activeAlbum = album
        mutableState.value = mutableState.value.copy(
            tab = AppTab.Photos,
            selectedAlbum = album,
            photos = emptyList(),
            loading = true,
            hasMore = true,
            pageError = null,
            notice = null,
        )
        viewModelScope.launch { loadPage(0, append = false) }
    }

    /** Returns from an opened album to Collections without unwinding the rest of the app. */
    fun backFromAlbum() {
        val current = mutableState.value
        if (activeAlbum?.requiresDeviceAuthentication() == true) clearProtectedAlbumContent()
        else current.videoPreviewUri?.let { it.toUri().path?.let(::File)?.delete() }
        activeAlbum = null
        mutableState.value = current.copy(
            tab = AppTab.Collections,
            selectedAlbum = null,
            selectedPhoto = null,
            photos = emptyList(),
            loading = false,
            loadingMore = false,
            hasMore = true,
            pageError = null,
            videoPreviewUri = null,
            videoPreviewAssetId = null,
            preparingVideoPreview = false,
            notice = null,
        )
        loadAlbums()
    }

    fun showAllPhotos() {
        val leavingProtectedAlbum = activeAlbum?.requiresDeviceAuthentication() == true
        if (leavingProtectedAlbum) clearProtectedAlbumContent()
        activeAlbum = null
        mutableState.value = mutableState.value.copy(
            tab = AppTab.Photos,
            selectedAlbum = null,
            selectedPhoto = null,
            photos = emptyList(),
            loading = true,
            loadingMore = false,
            hasMore = true,
            pageError = null,
            videoPreviewUri = null,
            videoPreviewAssetId = null,
            preparingVideoPreview = false,
        )
        viewModelScope.launch { loadPage(0, append = false) }
    }

    /** Lock private collections as soon as the app leaves the foreground. */
    fun lockProtectedAlbum() {
        if (activeAlbum?.requiresDeviceAuthentication() != true) return
        clearProtectedAlbumContent()
        activeAlbum = null
        reloadPublicPhotosOnForeground = true
        mutableState.value = mutableState.value.copy(
            tab = AppTab.Photos,
            selectedAlbum = null,
            selectedPhoto = null,
            photos = emptyList(),
            loading = false,
            loadingMore = false,
            hasMore = true,
            pageError = null,
            videoPreviewUri = null,
            videoPreviewAssetId = null,
            preparingVideoPreview = false,
            notice = null,
        )
    }

    fun onAppForegrounded() {
        if (!reloadPublicPhotosOnForeground) return
        reloadPublicPhotosOnForeground = false
        if (mutableState.value.authState is AuthState.Authenticated) {
            viewModelScope.launch { loadFirstPage() }
        }
    }

    private fun clearProtectedAlbumContent() {
        val current = mutableState.value
        val ids = current.photos.map(PhotoAsset::id)
        ids.forEach { id ->
            thumbnailCache.remove(id)
            previewCache.remove(id)
        }
        current.videoPreviewUri?.let { it.toUri().path?.let(::File)?.delete() }
        if (ids.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) thumbnailDiskCache.remove(id)
        }
    }

    fun loadMore() {
        val snapshot = mutableState.value
        if (snapshot.loading || snapshot.loadingMore || snapshot.syncing || !snapshot.hasMore || snapshot.pageError != null) return
        mutableState.value = snapshot.copy(loadingMore = true)
        viewModelScope.launch { loadPage(snapshot.photos.size, append = true) }
    }

    fun retryPhotoPage() {
        val snapshot = mutableState.value
        if (snapshot.loading || snapshot.loadingMore || snapshot.syncing) return
        mutableState.value = snapshot.copy(pageError = null, notice = null)
        if (snapshot.photos.isEmpty()) {
            viewModelScope.launch {
                mutableState.value = mutableState.value.copy(loading = true)
                loadPage(0, append = false)
            }
        } else {
            mutableState.value = mutableState.value.copy(loadingMore = true)
            viewModelScope.launch { loadPage(snapshot.photos.size, append = true) }
        }
    }

    fun loadAlbums() {
        if (albumLoadInProgress) {
            if (mutableState.value.tab == AppTab.Collections && mutableState.value.albums.isEmpty()) {
                mutableState.value = mutableState.value.copy(loading = true)
            }
            return
        }
        albumLoadInProgress = true
        viewModelScope.launch {
            try {
                val cached = runCatching { library.cachedAlbums() }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    publishAlbums(cached)
                    if (mutableState.value.tab == AppTab.Collections) {
                        mutableState.value = mutableState.value.copy(loading = false)
                    }
                } else if (mutableState.value.tab == AppTab.Collections) {
                    mutableState.value = mutableState.value.copy(loading = true)
                }
                val result = runCatching { library.albums() }
                result.onSuccess(::publishAlbums)
                result.exceptionOrNull()?.let {
                    mutableState.value = mutableState.value.copy(notice = userFacingFailure(it, app.getString(R.string.albums_could_not_load)))
                }
            } finally {
                albumLoadInProgress = false
                if (mutableState.value.tab == AppTab.Collections) {
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
    }

    private fun publishAlbums(fetched: List<PhotoAlbum>) {
        val albums = fetched.map { if (it.requiresDeviceAuthentication()) it.copy(coverAssetId = null) else it }
        val byId = albums.associateBy(PhotoAlbum::id)
        val current = mutableState.value
        val covers = current.albumCoverAssets.filter { (albumId, asset) ->
            val album = byId[albumId]
            album != null && !album.requiresDeviceAuthentication() && album.coverAssetId == asset.id
        }
        mutableState.value = current.copy(albums = albums, albumCoverAssets = covers)
    }

    fun ensureAlbumCover(album: PhotoAlbum) {
        if (album.requiresDeviceAuthentication() || album.assetCount <= 0 || mutableState.value.albumCoverAssets.containsKey(album.id)) return
        if (!albumCoverRequestsInFlight.add(album.id)) return
        viewModelScope.launch {
            try {
                val cover = album.coverAssetId?.let { library.cachedAsset(it) }
                    ?: albumCoverRequests.withPermit { library.albumCover(album) }
                if (cover != null) {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        albums = current.albums.map { item -> if (item.id == album.id) item.copy(coverAssetId = cover.id) else item },
                        albumCoverAssets = current.albumCoverAssets + (album.id to cover),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the neutral album artwork; a later visit can retry the cover request.
            } finally {
                albumCoverRequestsInFlight.remove(album.id)
            }
        }
    }

    fun photoSelected(asset: PhotoAsset?) {
        val previous = mutableState.value
        if (asset?.id != previous.selectedPhoto?.id) {
            previous.videoPreviewUri?.let { it.toUri().path?.let(::File)?.delete() }
        }
        mutableState.value = previous.copy(
            selectedPhoto = asset,
            videoPreviewUri = if (asset?.id == previous.selectedPhoto?.id) previous.videoPreviewUri else null,
            videoPreviewAssetId = if (asset?.id == previous.selectedPhoto?.id) previous.videoPreviewAssetId else null,
            preparingVideoPreview = false,
            notice = null,
        )
    }

    fun navigatePhoto(direction: Int) {
        if (direction == 0) return
        val snapshot = mutableState.value
        val currentId = snapshot.selectedPhoto?.id ?: return
        val index = snapshot.photos.indexOfFirst { it.id == currentId }
        val next = snapshot.photos.getOrNull(index + direction) ?: return
        photoSelected(next)
    }

    fun prepareVideoPreview(asset: PhotoAsset, liveMotion: Boolean = false) {
        val snapshot = mutableState.value
        if (snapshot.videoPreviewAssetId == asset.id && snapshot.videoPreviewUri != null) return
        if (snapshot.preparingVideoPreview && snapshot.videoPreviewAssetId == asset.id) return
        snapshot.videoPreviewUri?.let { it.toUri().path?.let(::File)?.delete() }
        mutableState.value = snapshot.copy(
            videoPreviewUri = null,
            videoPreviewAssetId = asset.id,
            preparingVideoPreview = true,
            notice = null,
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val extension = if (liveMotion) "mov" else asset.title.substringAfterLast('.', "mp4")
                        .takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) } ?: "mp4"
                    val file = File.createTempFile("ocloud-preview-", ".$extension", app.cacheDir)
                    try {
                        val input = if (liveMotion) app.photosProvider.downloadLivePhotoMotion(asset)
                        else app.photosProvider.downloadOriginal(asset)
                        input.use { source -> FileOutputStream(file).use { sink -> source.copyTo(sink) } }
                        file
                    } catch (failure: Exception) {
                        file.delete()
                        throw failure
                    }
                }
            }
            if (mutableState.value.selectedPhoto?.id != asset.id) {
                result.getOrNull()?.delete()
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                videoPreviewUri = result.getOrNull()?.let { Uri.fromFile(it).toString() },
                videoPreviewAssetId = asset.id,
                preparingVideoPreview = false,
                notice = result.exceptionOrNull()?.let { userFacingFailure(it, app.getString(R.string.video_preview_could_not_load)) }
                    ?: mutableState.value.notice,
            )
        }
    }

    fun shareOriginal(asset: PhotoAsset, onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(downloadInProgress = asset.id, notice = null)
            val result = runCatching { downloads.download(asset) }
            result.onSuccess { uri -> onReady(uri) }
            result.exceptionOrNull()?.let {
                mutableState.value = mutableState.value.copy(notice = userFacingFailure(it, app.getString(R.string.original_share_could_not_prepare)))
            }
            mutableState.value = mutableState.value.copy(downloadInProgress = null)
        }
    }

    suspend fun thumbnail(asset: PhotoAsset): ByteArray? = withContext(Dispatchers.IO) {
        val protectedAlbum = activeAlbum?.requiresDeviceAuthentication() == true
        thumbnailWarmups[asset.id]?.await()
        thumbnailCache.get(asset.id)?.let { return@withContext it }
        if (!protectedAlbum) {
            thumbnailDiskCache.get(asset.id)?.let { bytes ->
                thumbnailCache.put(asset.id, bytes)
                return@withContext bytes
            }
        }
        thumbnailRequests.withPermit {
            thumbnailCache.get(asset.id)?.let { return@withPermit it }
            if (!protectedAlbum) {
                thumbnailDiskCache.get(asset.id)?.let { bytes ->
                    thumbnailCache.put(asset.id, bytes)
                    return@withPermit bytes
                }
            }
            app.photosProvider.getThumbnail(asset)?.also {
                thumbnailCache.put(asset.id, it)
                if (!protectedAlbum) thumbnailDiskCache.put(asset.id, it)
            }
        }
    }

    suspend fun preview(asset: PhotoAsset): ByteArray? = withContext(Dispatchers.IO) {
        previewCache.get(asset.id)?.let { return@withContext it }
        app.photosProvider.getPreview(asset)?.also { previewCache.put(asset.id, it) }
    }

    fun download(asset: PhotoAsset) {
        viewModelScope.launch {
            val queued = runCatching { downloadQueue.enqueue(asset) }.getOrElse {
                mutableState.value = mutableState.value.copy(notice = app.getString(R.string.download_could_not_queue))
                false
            }
            mutableState.value = mutableState.value.copy(
                notice = app.getString(if (queued) R.string.media_queued else R.string.media_already_queued),
            )
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        LibrarySyncScheduler.setEnabled(app, enabled)
        mutableState.value = mutableState.value.copy(
            autoSyncEnabled = enabled,
            notice = app.getString(if (enabled) R.string.automatic_sync_enabled_notice else R.string.automatic_sync_paused_notice),
        )
    }

    fun dismissNotice() { mutableState.value = mutableState.value.copy(notice = null) }

    fun showNotice(message: String) { mutableState.value = mutableState.value.copy(notice = message) }

    private suspend fun loadFirstPage() {
        if (mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(loading = true, photos = emptyList(), hasMore = true, pageError = null)
        loadPage(0, append = false)
    }

    private suspend fun loadPage(offset: Int, append: Boolean) {
        val result = runCatching {
            when {
                mutableState.value.tab == AppTab.Downloads -> library.downloadedPage(offset, PAGE_SIZE)
                activeAlbum != null -> library.albumPage(activeAlbum!!.id, offset, PAGE_SIZE)
                else -> library.page(offset, PAGE_SIZE)
            }
        }
        result.onSuccess { page ->
            val pageAssets = page.assets.distinctBy(PhotoAsset::id)
            val previousPhotos = if (append) mutableState.value.photos else emptyList()
            val mergedPhotos = (previousPhotos + pageAssets).distinctBy(PhotoAsset::id)
            val warmupAssets = if (activeAlbum?.requiresDeviceAuthentication() == true) emptyList() else pageAssets.filter { asset ->
                thumbnailCache.get(asset.id) == null && thumbnailDiskCache.get(asset.id) == null
            }
            if (warmupAssets.isNotEmpty()) {
                val warmup = CompletableDeferred<Unit>()
                val warmupIds = warmupAssets.map(PhotoAsset::id)
                warmupIds.forEach { thumbnailWarmups[it] = warmup }
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        app.photosProvider.prepareThumbnails(warmupAssets)
                    } finally {
                        warmupIds.forEach { thumbnailWarmups.remove(it, warmup) }
                        warmup.complete(Unit)
                    }
                }
            }
            val noProgress = pageAssets.isEmpty() || (append && mergedPhotos.size == previousPhotos.size)
            val stalled = noProgress && page.hasMore
            mutableState.value = mutableState.value.copy(
                photos = mergedPhotos,
                hasMore = page.hasMore,
                pageError = if (stalled) app.getString(R.string.page_no_new_photos, app.getString(R.string.brand_apple)) else null,
                notice = null,
            )
        }
        result.exceptionOrNull()?.let {
            mutableState.value = mutableState.value.copy(
                pageError = userFacingFailure(it, app.getString(R.string.library_could_not_load, app.getString(R.string.brand_iphotos))),
                notice = null,
            )
        }
        mutableState.value = mutableState.value.copy(loading = false, loadingMore = false)
    }

    private suspend fun userFacingFailure(failure: Throwable, fallback: String): String {
        val normalized = failure.message.orEmpty().lowercase()
        if (normalized.contains("session expired") || normalized.contains("sign in again")) {
            app.authManager.invalidateExpiredSession()
            return app.getString(R.string.session_expired, app.getString(R.string.brand_apple))
        }
        if (normalized.contains("limit") || normalized.contains("429")) {
            return app.getString(R.string.apple_rate_limited, app.getString(R.string.brand_apple))
        }
        return fallback
    }

    private fun loadDownloads() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, photos = emptyList(), hasMore = true, pageError = null)
            loadPage(0, append = false)
        }
    }

    class Factory(private val app: OCloudApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OCloudViewModel::class.java))
            return OCloudViewModel(app) as T
        }
    }

    private companion object {
        const val PAGE_SIZE = 24
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
