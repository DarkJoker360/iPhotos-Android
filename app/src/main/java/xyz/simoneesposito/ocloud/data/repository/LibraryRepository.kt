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

package xyz.simoneesposito.ocloud.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.simoneesposito.ocloud.data.database.CloudAssetEntity
import xyz.simoneesposito.ocloud.data.database.AlbumEntity
import xyz.simoneesposito.ocloud.data.database.OCloudDatabase
import xyz.simoneesposito.ocloud.data.database.SyncStateEntity
import xyz.simoneesposito.ocloud.data.database.toDomain
import xyz.simoneesposito.ocloud.data.database.toEntity
import xyz.simoneesposito.ocloud.domain.model.MediaKind
import xyz.simoneesposito.ocloud.domain.model.PhotoAlbum
import xyz.simoneesposito.ocloud.domain.model.requiresDeviceAuthentication
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import xyz.simoneesposito.ocloud.domain.model.SyncState
import xyz.simoneesposito.ocloud.domain.provider.PhotosProvider
import xyz.simoneesposito.ocloud.domain.provider.ProviderUnavailableException

data class LocalPhotoPage(val assets: List<PhotoAsset>, val hasMore: Boolean)

/** Coordinates Room's stable local pages with the remote album cursor. */
class LibraryRepository(
    private val database: OCloudDatabase,
    private val provider: PhotosProvider,
) {
    private val cloudAssets = database.cloudAssetDao()
    private val syncStates = database.syncStateDao()
    private val pagingVersionMutex = Mutex()

    suspend fun page(offset: Int, limit: Int): LocalPhotoPage {
        ensurePagingCursorVersion()
        val state = syncStates.get(PAGING_STATE_ID)
        var cursor = state?.opaqueToken
        var count = cloudAssets.count()
        var exhausted = cursor == ""
        var noProgressPages = 0
        val targetCount = offset + limit
        while (!exhausted && count < targetCount) {
            val remote = provider.getLibraryPage(cursor?.takeIf(String::isNotEmpty), PAGE_SIZE)
            val nextCursor = remote.nextCursor ?: ""
            if (nextCursor.isNotEmpty() && nextCursor == cursor) {
                throw ProviderUnavailableException("Apple Photos did not advance the library page. Wait a moment, then retry.")
            }
            persistPage(remote.assets)
            cursor = nextCursor
            exhausted = cursor.isEmpty()
            syncStates.upsert(
                SyncStateEntity(
                    libraryId = PAGING_STATE_ID,
                    opaqueToken = cursor,
                    lastSuccessfulSyncMillis = System.currentTimeMillis(),
                ),
            )
            val newCount = cloudAssets.count()
            if (newCount == count && !exhausted) {
                noProgressPages += 1
                if (noProgressPages >= MAX_NO_PROGRESS_PAGES) {
                    throw ProviderUnavailableException("Apple Photos advanced its cursor without returning new photos. Retry to continue.")
                }
            } else {
                noProgressPages = 0
            }
            count = newCount
        }
        val rows = cloudAssets.page(offset, limit).map { it.toDomain() }
        val mayHaveMore = !exhausted || offset + rows.size < count
        return LocalPhotoPage(rows, mayHaveMore)
    }

    suspend fun albumPage(albumId: String, offset: Int, limit: Int): LocalPhotoPage {
        ensurePagingCursorVersion()
        val stateId = "icloud-photos-album-page-v3:$albumId"
        val state = syncStates.get(stateId)
        var cursor = state?.opaqueToken
        var count = cloudAssets.countForAlbum(albumId)
        var exhausted = cursor == ""
        var noProgressPages = 0
        val targetCount = offset + limit
        while (!exhausted && count < targetCount) {
            val remote = provider.getAlbumPage(albumId, cursor?.takeIf(String::isNotEmpty), PAGE_SIZE)
            val nextCursor = remote.nextCursor ?: ""
            if (nextCursor.isNotEmpty() && nextCursor == cursor) {
                throw ProviderUnavailableException("Apple Photos did not advance this album page. Wait a moment, then retry.")
            }
            persistPage(remote.assets)
            cursor = nextCursor
            exhausted = cursor.isEmpty()
            syncStates.upsert(
                SyncStateEntity(stateId, cursor, System.currentTimeMillis()),
            )
            val newCount = cloudAssets.countForAlbum(albumId)
            if (newCount == count && !exhausted) {
                noProgressPages += 1
                if (noProgressPages >= MAX_NO_PROGRESS_PAGES) {
                    throw ProviderUnavailableException("Apple Photos advanced this album cursor without returning new photos. Retry to continue.")
                }
            } else {
                noProgressPages = 0
            }
            count = newCount
        }
        val rows = cloudAssets.pageForAlbum(albumId, offset, limit).map { it.toDomain() }
        return LocalPhotoPage(rows, !exhausted || offset + rows.size < count)
    }

    suspend fun downloadedPage(offset: Int, limit: Int): LocalPhotoPage {
        val rows = cloudAssets.downloadedPage(offset, limit)
        val count = cloudAssets.downloadedCount()
        return LocalPhotoPage(rows.map { it.toDomain() }, offset + rows.size < count)
    }

    suspend fun cachedAlbums(): List<PhotoAlbum> = database.albumDao().getAllOnce().map(AlbumEntity::toDomain)

    suspend fun albums(): List<PhotoAlbum> {
        val fetched = provider.getAlbums()
        val savedCovers = database.albumDao().getAllOnce().associate { it.albumId to it.coverAssetId }
        val snapshot = fetched.map { album ->
            if (album.requiresDeviceAuthentication()) album.copy(coverAssetId = null, requiresAuthentication = true)
            else album.copy(coverAssetId = album.coverAssetId ?: savedCovers[album.id])
        }
        database.withTransaction {
            database.albumDao().clearAlbums()
            if (snapshot.isNotEmpty()) database.albumDao().upsertAll(snapshot.map(PhotoAlbum::toEntity))
        }
        return snapshot
    }

    suspend fun albumCover(album: PhotoAlbum): PhotoAsset? {
        if (album.assetCount <= 0) return null
        val cached = album.coverAssetId?.let { cloudAssets.getActive(it)?.toDomain() }
        if (cached != null) return cached
        val newest = albumPage(album.id, offset = 0, limit = 1).assets.firstOrNull()
        if (newest != null) database.albumDao().setCoverAssetId(album.id, newest.id)
        return newest
    }

    suspend fun cachedAsset(assetId: String): PhotoAsset? = cloudAssets.getActive(assetId)?.toDomain()

    suspend fun sync() {
        val previous = cloudAssets.activeAssets().associateBy(CloudAssetEntity::assetId)
        val storedState = syncStates.get(SYNC_STATE_ID)
        val result = provider.sync(SyncState(storedState?.opaqueToken))
        database.withTransaction {
            upsertPreservingDownloadFlags(result.changedAssets, previous)
            persistMemberships(result.changedAssets)
            val deleted = if (result.fullSnapshot) {
                val currentIds = result.changedAssets.mapTo(HashSet()) { it.id }
                previous.keys.filterNot(currentIds::contains)
            } else {
                result.deletedAssetIds.toList()
            }
            if (deleted.isNotEmpty()) cloudAssets.markDeleted(deleted)
            syncStates.upsert(
                SyncStateEntity(
                    libraryId = SYNC_STATE_ID,
                    opaqueToken = result.nextState.token,
                    lastSuccessfulSyncMillis = System.currentTimeMillis(),
                    fullRefreshRequired = result.fullRefreshRequired,
                ),
            )
        }
    }

    suspend fun markDownloaded(assetId: String) = cloudAssets.markDownloaded(assetId)

    private suspend fun upsertPreservingDownloadFlags(
        assets: List<PhotoAsset>,
        previous: Map<String, CloudAssetEntity>? = null,
    ) {
        if (assets.isNotEmpty()) {
            val oldAssets = previous ?: cloudAssets.activeAssets().associateBy(CloudAssetEntity::assetId)
            cloudAssets.upsertAll(assets.map { it.toEntity(oldAssets[it.id]?.isDownloaded == true) })
        }
    }

    private suspend fun persistPage(assets: List<PhotoAsset>) {
        upsertPreservingDownloadFlags(assets)
        persistMemberships(assets)
    }

    private suspend fun persistMemberships(assets: List<PhotoAsset>) {
        val memberships = assets.flatMap { asset ->
            asset.albumIds.map { albumId ->
                xyz.simoneesposito.ocloud.data.database.AlbumMembershipEntity(albumId, asset.id)
            }
        }
        if (memberships.isNotEmpty()) database.albumDao().upsertMemberships(memberships)
    }

    /**
     * The previous native cursor walked count-1 downward while also requesting
     * descending order. Its persisted pages can therefore omit the newest end
     * of the library. Drop only that partial, undownloaded cache once when
     * upgrading the cursor scheme; downloaded items remain available offline.
     */
    private suspend fun ensurePagingCursorVersion() = pagingVersionMutex.withLock {
        if (syncStates.get(PAGING_VERSION_STATE_ID)?.opaqueToken == PAGING_VERSION) return@withLock
        val hasLegacyPages = syncStates.hasLegacyPhotoPageCursors()
        database.withTransaction {
            if (hasLegacyPages) {
                cloudAssets.invalidateUndownloadedRemoteCache()
                database.albumDao().clearMemberships()
            }
            syncStates.clearLegacyPhotoPageCursors()
            syncStates.upsert(
                SyncStateEntity(
                    libraryId = PAGING_VERSION_STATE_ID,
                    opaqueToken = PAGING_VERSION,
                    lastSuccessfulSyncMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private companion object {
        const val PAGING_STATE_ID = "icloud-photos-all-page-cursor-v3"
        const val PAGING_VERSION_STATE_ID = "icloud-photos-page-version"
        const val PAGING_VERSION = "descending-start-rank-offset-v5-location-encodings"
        const val SYNC_STATE_ID = "icloud-photos-sync-state"
        const val PAGE_SIZE = 120
        const val MAX_NO_PROGRESS_PAGES = 3
    }
}

/** Streams an original into MediaStore so it appears in the user's Android library. */
class PhotoDownloadRepository(
    private val context: Context,
    private val provider: PhotosProvider,
    private val library: LibraryRepository,
) {
    suspend fun download(
        asset: PhotoAsset,
        onPendingUrisChanged: suspend (List<Uri>) -> Unit = {},
    ): Uri {
        val extension = asset.title.substringAfterLast('.', "jpg").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val isVideo = asset.mediaKind == MediaKind.Video
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val displayName = asset.title.replace('/', '_').replace('\\', '_')
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/iPhotos" else "Pictures/iPhotos")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, asset.capturedAtMillis)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("Android could not create the downloaded file")
        val pendingUris = mutableListOf(uri)
        var motionUri: Uri? = null
        try {
            onPendingUrisChanged(pendingUris.toList())
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: error("Android could not open the downloaded file")
            provider.downloadOriginal(asset).use { input -> output.use { sink -> input.copyTo(sink) } }
            if (asset.liveMotionResourceKey != null) {
                val motionName = displayName.substringBeforeLast('.', displayName) + ".MOV"
                val motionValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, motionName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/quicktime")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/iPhotos")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.Video.VideoColumns.DATE_TAKEN, asset.capturedAtMillis)
                }
                val createdMotionUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, motionValues)
                    ?: error("Android could not create the Live Photo motion file")
                motionUri = createdMotionUri
                pendingUris.add(createdMotionUri)
                onPendingUrisChanged(pendingUris.toList())
                val motionOutput = context.contentResolver.openOutputStream(createdMotionUri, "w")
                    ?: error("Android could not open the Live Photo motion file")
                provider.downloadLivePhotoMotion(asset).use { input -> motionOutput.use { sink -> input.copyTo(sink) } }
            }
            val visible = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, visible, null, null)
            motionUri?.let { context.contentResolver.update(it, visible, null, null) }
            library.markDownloaded(asset.id)
            return uri
        } catch (failure: Exception) {
            context.contentResolver.delete(uri, null, null)
            motionUri?.let { context.contentResolver.delete(it, null, null) }
            throw failure
        }
    }
}
