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

package xyz.simoneesposito.ocloud.data.provider

import android.content.Context
import androidx.room.withTransaction
import icloudbridge.icloudbridge.Bridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import xyz.simoneesposito.ocloud.data.database.OCloudDatabase
import xyz.simoneesposito.ocloud.domain.model.MediaKind
import xyz.simoneesposito.ocloud.domain.model.PhotoAlbum
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import xyz.simoneesposito.ocloud.domain.model.requiresDeviceAuthentication
import xyz.simoneesposito.ocloud.domain.model.PhotoPage
import xyz.simoneesposito.ocloud.domain.model.SyncResult
import xyz.simoneesposito.ocloud.domain.model.SyncState
import xyz.simoneesposito.ocloud.domain.model.UploadAsset
import xyz.simoneesposito.ocloud.domain.model.UploadResult
import xyz.simoneesposito.ocloud.domain.provider.PhotosProvider
import xyz.simoneesposito.ocloud.domain.provider.ProviderUnavailableException
import androidx.core.net.toUri

/** Real iCloud Photos provider backed by rclone's maintained Apple protocol client. */
class ICloudPhotosProvider(
    private val context: Context,
    private val bridgeProvider: suspend () -> Bridge,
    private val database: OCloudDatabase,
) : PhotosProvider {
    @Volatile private var cachedAlbums: List<PhotoAlbum>? = null
    @Volatile private var allPhotosAlbumIds: List<String> = emptyList()
    private val albumsMutex = Mutex()
    private val thumbnailUrls = ConcurrentHashMap<String, String>()
    private val thumbnailUrlOrder = ArrayDeque<String>()
    private val thumbnailUrlLock = Any()
    private val thumbnailLookupMutex = Mutex()
    @Volatile private var thumbnailLookupCooldownUntil = 0L
    @Volatile private var thumbnailLookupCooldownMessage: String? = null
    @Volatile private var thumbnailCdnCooldownUntil = 0L

    fun clearSessionCache() {
        cachedAlbums = null
        allPhotosAlbumIds = emptyList()
        synchronized(thumbnailUrlLock) {
            thumbnailUrls.clear()
            thumbnailUrlOrder.clear()
        }
        thumbnailLookupCooldownUntil = 0L
        thumbnailLookupCooldownMessage = null
        thumbnailCdnCooldownUntil = 0L
    }

    override suspend fun getLibraryPage(cursor: String?, pageSize: Int): PhotoPage = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        val albumIds = allPhotosAlbumIds.ifEmpty { getAlbums(); allPhotosAlbumIds }
        if (albumIds.isEmpty()) throw ProviderUnavailableException("Apple did not return an All Photos library.")
        val cursorParts = cursor?.split('|', limit = 2)
        var albumIndex = cursorParts?.getOrNull(0)?.toIntOrNull() ?: 0
        var offset = cursorParts?.getOrNull(1)?.toIntOrNull() ?: 0
        albumIndex = albumIndex.coerceIn(0, albumIds.lastIndex)
        val assets = mutableListOf<PhotoAsset>()
        var nextCursor: String? = null
        while (assets.size < pageSize && albumIndex < albumIds.size) {
            val response = JSONObject(bridge.photosPage(albumIds[albumIndex], offset.toString(), (pageSize - assets.size).toLong())).toPhotoPage()
            assets += response.assets
            if (response.nextCursor != null) {
                nextCursor = "$albumIndex|${response.nextCursor}"
                break
            }
            albumIndex += 1
            offset = 0
            if (albumIndex < albumIds.size) nextCursor = "$albumIndex|0"
        }
        PhotoPage(assets, nextCursor)
    }

    override suspend fun getAlbumPage(albumId: String, cursor: String?, pageSize: Int): PhotoPage = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        getAlbums()
        JSONObject(bridge.photosPage(albumId, cursor.orEmpty(), pageSize.toLong())).toPhotoPage()
    }

    override suspend fun getAlbums(): List<PhotoAlbum> = withContext(Dispatchers.IO) {
        albumsMutex.withLock {
            cachedAlbums?.let { return@withLock it }
            loadAlbumsLocked()
        }
    }

    private suspend fun loadAlbumsLocked(): List<PhotoAlbum> {
        val bridge = bridgeProvider()
        val savedCoverIds = database.albumDao().getAllOnce().associate { it.albumId to it.coverAssetId }
        val response = JSONArray(bridge.albums())
        val albums = (0 until response.length()).mapNotNull { index ->
            response.optJSONObject(index)?.let { row ->
                val album = PhotoAlbum(
                    id = row.getString("id"),
                    title = row.getString("title"),
                    assetCount = row.optInt("assetCount"),
                    coverAssetId = if (row.optBoolean("requiresAuthentication")) null else {
                        row.optString("coverAssetId").takeIf(String::isNotBlank)
                            ?: savedCoverIds[row.getString("id")]
                    },
                    libraryId = row.optString("libraryId"),
                    requiresAuthentication = row.optBoolean("requiresAuthentication"),
                )
                if (album.requiresDeviceAuthentication()) album.copy(coverAssetId = null, requiresAuthentication = true) else album
            }
        }
        allPhotosAlbumIds = albums.filter { it.title == "All Photos" }.map(PhotoAlbum::id)
        cachedAlbums = albums
        database.withTransaction {
            database.albumDao().clearAlbums()
            if (albums.isNotEmpty()) database.albumDao().upsertAll(albums.map { it.toDatabaseAlbum() })
        }
        return albums
    }

    private suspend fun refreshAlbums() = withContext(Dispatchers.IO) {
        albumsMutex.withLock {
            // Keep the last complete ID snapshot available to in-flight pages
            // until Bridge.Albums atomically replaces its native album handles.
            cachedAlbums = null
            loadAlbumsLocked()
        }
    }

    override suspend fun getThumbnail(asset: PhotoAsset): ByteArray? = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        val file = File.createTempFile("ocloud-thumb-", ".jpg", context.cacheDir)
        try {
            val cachedUrl = cachedThumbnailUrl(asset.id) ?: asset.thumbnailDownloadUrl
            if (cachedUrl != null) {
                if (System.currentTimeMillis() < thumbnailCdnCooldownUntil) {
                    throw ProviderUnavailableException("Apple is temporarily limiting photo requests. Wait a moment, then try again.")
                }
                try {
                    bridge.downloadURL(cachedUrl, file.absolutePath)
                } catch (failure: Exception) {
                    if (failure !is CancellationException && failure.message?.contains("link expired", ignoreCase = true) == true) {
                        forgetThumbnailUrl(asset.id)
                        bridge.download(asset.id, THUMBNAIL_RESOURCE, file.absolutePath)
                    } else {
                        throw failure
                    }
                }
            } else {
                val cooldown = thumbnailLookupCooldownMessage
                if (System.currentTimeMillis() < thumbnailLookupCooldownUntil && cooldown != null) {
                    throw ProviderUnavailableException(cooldown)
                }
                bridge.download(asset.id, THUMBNAIL_RESOURCE, file.absolutePath)
            }
            FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure.message?.contains("limiting", ignoreCase = true) == true || failure.message?.contains("429") == true) {
                thumbnailLookupCooldownMessage = "Apple is temporarily limiting photo requests. Wait a moment, then try again."
                thumbnailLookupCooldownUntil = System.currentTimeMillis() + THUMBNAIL_LOOKUP_COOLDOWN_MS
                thumbnailCdnCooldownUntil = thumbnailLookupCooldownUntil
            }
            throw ProviderUnavailableException(failure.message ?: "Could not load this photo from Apple.")
        } finally {
            file.delete()
            File(file.absolutePath + ".part").delete()
        }
    }

    /** Resolve cached-page images in batches instead of issuing one Apple lookup per tile. */
    override suspend fun prepareThumbnails(assets: List<PhotoAsset>) = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        thumbnailLookupMutex.withLock {
            val missing = assets.asSequence().map(PhotoAsset::id).distinct()
                .filter { cachedThumbnailUrl(it) == null }
                .toList()
            for (batch in missing.chunked(THUMBNAIL_LOOKUP_BATCH_SIZE)) {
                try {
                    val response = JSONObject(bridge.resolveThumbnailURLs(JSONArray(batch).toString()))
                    batch.forEach { id -> response.optString(id).takeIf(String::isNotBlank)?.let { rememberThumbnailUrl(id, it) } }
                    val unresolved = batch.any { cachedThumbnailUrl(it) == null }
                    if (unresolved) {
                        thumbnailLookupCooldownMessage = "Some photo previews are temporarily unavailable. Try again in a moment."
                        thumbnailLookupCooldownUntil = System.currentTimeMillis() + THUMBNAIL_LOOKUP_FAILURE_COOLDOWN_MS
                    } else {
                        thumbnailLookupCooldownUntil = 0L
                        thumbnailLookupCooldownMessage = null
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    val message = failure.message ?: "Apple photo links could not be refreshed. Try again in a moment."
                    if (message.contains("limiting", ignoreCase = true) || message.contains("429")) {
                        thumbnailLookupCooldownMessage = "Apple is temporarily limiting photo requests. Wait a moment, then try again."
                        thumbnailLookupCooldownUntil = System.currentTimeMillis() + THUMBNAIL_LOOKUP_COOLDOWN_MS
                    } else {
                        thumbnailLookupCooldownMessage = "Apple photo links could not be refreshed. Check your connection, then try again."
                        thumbnailLookupCooldownUntil = System.currentTimeMillis() + THUMBNAIL_LOOKUP_FAILURE_COOLDOWN_MS
                    }
                    break
                }
            }
        }
    }

    override suspend fun getPreview(asset: PhotoAsset): ByteArray? = withContext(Dispatchers.IO) {
        if (asset.mediaKind == MediaKind.Video) return@withContext null
        val medium = runCatching {
            downloadResource(asset, MEDIUM_RESOURCE).use { it.readBytes() }
        }.getOrNull()
        medium ?: getThumbnail(asset)
    }

    override suspend fun downloadOriginal(asset: PhotoAsset): InputStream = downloadResource(asset, asset.resourceKey)

    override suspend fun downloadLivePhotoMotion(asset: PhotoAsset): InputStream {
        val resourceKey = asset.liveMotionResourceKey
            ?: throw ProviderUnavailableException("This asset does not include a Live Photo motion component.")
        return downloadResource(asset, resourceKey)
    }

    private suspend fun downloadResource(asset: PhotoAsset, resourceKey: String): InputStream = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        val extension = asset.title.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
            ?.let { ".$it" } ?: ".bin"
        val file = File.createTempFile("icloud-original-", extension, context.cacheDir)
        try {
            bridge.download(asset.id, resourceKey, file.absolutePath)
            object : FilterInputStream(FileInputStream(file)) {
                override fun close() {
                    try { super.close() } finally { file.delete() }
                }
            }
        } catch (failure: Exception) {
            file.delete()
            File(file.absolutePath + ".part").delete()
            throw ProviderUnavailableException(failure.message ?: "Could not download the original from Apple.")
        }
    }

    override suspend fun uploadAsset(asset: UploadAsset): UploadResult = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        val source = asset.contentUri.toUri()
        val staged = File.createTempFile("icloud-upload-", ".asset", context.cacheDir)
        try {
            val input = (if (source.scheme == "file") source.path?.let(::File)?.inputStream()
                else context.contentResolver.openInputStream(source))
                ?: throw ProviderUnavailableException("Android could not open the selected photo.")
            input.use { from -> staged.outputStream().use { to -> from.copyTo(to) } }
            when (bridge.upload(asset.displayName, staged.absolutePath)) {
                "accepted" -> UploadResult(assetId = null, acceptedByApple = true)
                "duplicate" -> UploadResult(assetId = null, acceptedByApple = true, duplicate = true)
                else -> throw ProviderUnavailableException("Apple returned an unknown upload response.")
            }
        } catch (failure: ProviderUnavailableException) {
            throw failure
        } catch (failure: Exception) {
            throw ProviderUnavailableException(failure.message ?: "The photo could not be uploaded to Apple.")
        } finally {
            staged.delete()
        }
    }

    override suspend fun addAssetToAlbum(assetId: String, albumId: String) {
        throw ProviderUnavailableException("Apple's currently supported iPhotos client API is read-only for album membership.")
    }

    override suspend fun sync(state: SyncState): SyncResult = withContext(Dispatchers.IO) {
        refreshAlbums()
        val updated = mutableListOf<PhotoAsset>()
        var cursor: String? = null
        do {
            val page = getLibraryPage(cursor, SYNC_PAGE_SIZE)
            updated += page.assets
            cursor = page.nextCursor
        } while (cursor != null)
        SyncResult(
            changedAssets = updated,
            deletedAssetIds = emptySet(),
            nextState = SyncState(token = null),
            fullRefreshRequired = false,
            fullSnapshot = true,
        )
    }

    private fun JSONObject.toPhotoAsset() = PhotoAsset(
        id = getString("id"),
        title = optString("title").ifBlank { "iPhotos photo" },
        capturedAtMillis = optLong("capturedAtMillis"),
        mediaKind = runCatching { MediaKind.valueOf(optString("mediaKind")) }.getOrDefault(MediaKind.Photo),
        sizeBytes = optLong("sizeBytes"),
        width = optInt("width"),
        height = optInt("height"),
        albumIds = optJSONArray("albumIds")?.let { ids ->
            (0 until ids.length()).mapNotNull { index -> ids.optString(index).takeIf(String::isNotBlank) }.toSet()
        }.orEmpty(),
        isFavorite = optBoolean("favorite"),
        resourceKey = optString("resourceKey").ifBlank { "resOriginalRes" },
        liveMotionResourceKey = optString("liveMotionResourceKey").takeIf(String::isNotBlank),
        libraryId = optString("libraryId"),
        orientation = if (has("orientation") && !isNull("orientation")) optInt("orientation") else null,
        durationMillis = optLong("durationMillis").takeIf { it > 0 },
        thumbnailDownloadUrl = optString("thumbnailUrl").takeIf(String::isNotBlank),
    )

    private fun JSONObject.toPhotoPage(): PhotoPage {
        val rows = optJSONArray("assets") ?: JSONArray()
        val assets = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.toPhotoAsset() }
        assets.forEach { asset -> asset.thumbnailDownloadUrl?.let { rememberThumbnailUrl(asset.id, it) } }
        return PhotoPage(assets, optString("nextCursor").takeIf(String::isNotEmpty))
    }

    private fun cachedThumbnailUrl(assetId: String): String? = thumbnailUrls[assetId]

    private fun rememberThumbnailUrl(assetId: String, downloadUrl: String) {
        synchronized(thumbnailUrlLock) {
            thumbnailUrls[assetId] = downloadUrl
            thumbnailUrlOrder.remove(assetId)
            thumbnailUrlOrder.addLast(assetId)
            while (thumbnailUrlOrder.size > THUMBNAIL_URL_CACHE_SIZE) {
                thumbnailUrls.remove(thumbnailUrlOrder.removeFirst())
            }
        }
    }

    private fun forgetThumbnailUrl(assetId: String) {
        synchronized(thumbnailUrlLock) {
            thumbnailUrls.remove(assetId)
            thumbnailUrlOrder.remove(assetId)
        }
    }

    private companion object {
        const val THUMBNAIL_RESOURCE = "resJPEGThumbRes"
        const val MEDIUM_RESOURCE = "resJPEGMedRes"
        const val SYNC_PAGE_SIZE = 120
        const val THUMBNAIL_LOOKUP_BATCH_SIZE = 50
        const val THUMBNAIL_URL_CACHE_SIZE = 256
        const val THUMBNAIL_LOOKUP_COOLDOWN_MS = 30_000L
        const val THUMBNAIL_LOOKUP_FAILURE_COOLDOWN_MS = 8_000L
    }
}

private fun PhotoAsset.toDatabaseAsset() = xyz.simoneesposito.ocloud.data.database.CloudAssetEntity(
    assetId = id,
    title = title,
    capturedAtMillis = capturedAtMillis,
    mediaKind = mediaKind.name,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    albumId = albumIds.firstOrNull(),
    isFavorite = isFavorite,
    artworkStyle = artworkStyle,
    resourceKey = resourceKey,
    liveMotionResourceKey = liveMotionResourceKey,
    libraryId = libraryId,
    orientation = orientation,
    durationMillis = durationMillis,
)

private fun PhotoAlbum.toDatabaseAlbum() = xyz.simoneesposito.ocloud.data.database.AlbumEntity(
    albumId = id,
    title = title,
    assetCount = assetCount,
    coverAssetId = coverAssetId,
    libraryId = libraryId,
    requiresAuthentication = requiresAuthentication,
)
