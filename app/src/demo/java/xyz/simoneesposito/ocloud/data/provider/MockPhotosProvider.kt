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

import xyz.simoneesposito.ocloud.domain.model.MediaKind
import xyz.simoneesposito.ocloud.domain.model.PhotoAlbum
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import xyz.simoneesposito.ocloud.domain.model.PhotoPage
import xyz.simoneesposito.ocloud.domain.model.SyncResult
import xyz.simoneesposito.ocloud.domain.model.SyncState
import xyz.simoneesposito.ocloud.domain.model.UploadAsset
import xyz.simoneesposito.ocloud.domain.model.UploadResult
import xyz.simoneesposito.ocloud.domain.provider.PhotosProvider
import java.time.LocalDate
import java.time.ZoneId
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A deterministic, offline-only provider for UI development and tests. */
class MockPhotosProvider(
    assetCount: Int = 12,
    private val thumbnailBytesByAssetId: Map<String, ByteArray> = emptyMap(),
) : PhotosProvider {
    private val assets = demoAssets(assetCount).toMutableList()
    private var sortedAssetsCache: List<PhotoAsset>? = null
    private var albums = listOf(
        PhotoAlbum("album-coast", "Coastlines", 4, "demo-01"),
        PhotoAlbum("album-city", "City notes", 4, "demo-05"),
        PhotoAlbum("album-weekend", "Weekend light", 5, "demo-09"),
        PhotoAlbum("album-favorites", "Favorites", 3, "demo-02"),
    )
    private var revision = 1
    private val changes = mutableListOf<Pair<Int, Change>>()
    var networkAvailable: Boolean = true
    var sessionExpired: Boolean = false
    var failNextUpload: Boolean = false
    var failNextSync: Boolean = false

    init { assets.forEach { changes += revision to Change.Upsert(it) } }

    override suspend fun getLibraryPage(cursor: String?, pageSize: Int): PhotoPage {
        checkAvailable()
        val offset = cursor?.toIntOrNull() ?: 0
        val safeSize = pageSize.coerceIn(1, 200)
        val ordered = sortedAssets()
        val start = offset.coerceIn(0, ordered.size)
        val items = ordered.subList(start, (start + safeSize).coerceAtMost(ordered.size))
        val next = (offset + items.size).takeIf { it < assets.size }?.toString()
        return PhotoPage(items, next)
    }

    override suspend fun getAlbumPage(albumId: String, cursor: String?, pageSize: Int): PhotoPage {
        checkAvailable()
        require(albums.any { it.id == albumId }) { "The demo album was not found" }
        val albumAssets = assets.filter { albumId in it.albumIds }
        val offset = cursor?.toIntOrNull() ?: 0
        val ordered = albumAssets.sortedByDescending(PhotoAsset::capturedAtMillis)
        val start = offset.coerceIn(0, ordered.size)
        val items = ordered.subList(start, (start + pageSize.coerceIn(1, 200)).coerceAtMost(ordered.size))
        val next = (offset + items.size).takeIf { it < albumAssets.size }?.toString()
        return PhotoPage(items, next)
    }

    override suspend fun getAlbums(): List<PhotoAlbum> { checkAvailable(); return albums }

    override suspend fun getThumbnail(asset: PhotoAsset): ByteArray {
        checkAvailable()
        return thumbnailBytesByAssetId[asset.id] ?: asset.id.toByteArray()
    }

    override suspend fun downloadOriginal(asset: PhotoAsset): InputStream {
        checkAvailable()
        return ByteArrayInputStream("iPhotos offline demo asset ${asset.id}".toByteArray())
    }

    override suspend fun downloadLivePhotoMotion(asset: PhotoAsset): InputStream {
        checkAvailable()
        check(!asset.liveMotionResourceKey.isNullOrBlank()) { "This demo asset has no Live Photo video." }
        return ByteArrayInputStream("iPhotos offline demo motion ${asset.id}".toByteArray())
    }

    override suspend fun uploadAsset(asset: UploadAsset): UploadResult {
        checkAvailable()
        if (failNextUpload) {
            failNextUpload = false
            error("The demo upload failed. Try again.")
        }
        val id = "demo-upload-${assets.size + 1}"
        val uploaded = PhotoAsset(
            id = id,
            title = asset.displayName,
            capturedAtMillis = asset.modifiedAtMillis,
            mediaKind = when (asset.displayName.substringAfterLast('.', "").lowercase()) {
                "mp4", "mov", "m4v", "avi" -> MediaKind.Video
                "dng", "cr2", "cr3", "nef", "arw", "raf", "rw2", "orf", "pef" -> MediaKind.Raw
                else -> MediaKind.Photo
            },
            sizeBytes = asset.sizeBytes,
            width = 0,
            height = 0,
            artworkStyle = "upload",
        )
        assets += uploaded
        record(Change.Upsert(uploaded))
        updateAlbumCounts()
        return UploadResult(id, acceptedByApple = false, simulated = true)
    }

    override suspend fun addAssetToAlbum(assetId: String, albumId: String) {
        checkAvailable()
        val assetIndex = assets.indexOfFirst { it.id == assetId }
        if (assetIndex < 0 || albums.none { it.id == albumId }) {
            throw NoSuchElementException("The demo asset or album was not found")
        }
        val updated = assets[assetIndex].copy(albumIds = assets[assetIndex].albumIds + albumId)
        assets[assetIndex] = updated
        record(Change.Upsert(updated))
        updateAlbumCounts()
    }

    override suspend fun sync(state: SyncState): SyncResult {
        checkAvailable()
        if (failNextSync) {
            failNextSync = false
            error("The demo sync failed. Try again.")
        }
        val current = "mock-revision-$revision"
        val knownRevision = state.token?.removePrefix("mock-revision-")?.toIntOrNull()
        val invalidToken = state.token != null && (knownRevision == null || knownRevision !in 0..revision)
        val changesSince = if (state.token == null || invalidToken) changes else changes.filter { it.first > knownRevision!! }
        val changedAssets = changesSince.mapNotNull { (_, change) -> (change as? Change.Upsert)?.asset }
            .distinctBy(PhotoAsset::id)
        val deleted = changesSince.mapNotNull { (_, change) -> (change as? Change.Delete)?.assetId }.toSet()
        return SyncResult(
            changedAssets = if (invalidToken) assets.toList() else changedAssets,
            deletedAssetIds = if (invalidToken) emptySet() else deleted,
            nextState = SyncState(current),
            fullRefreshRequired = invalidToken,
            fullSnapshot = invalidToken,
        )
    }

    fun allDemoAssets(): List<PhotoAsset> = assets.toList()

    fun modifyAsset(asset: PhotoAsset) {
        val index = assets.indexOfFirst { it.id == asset.id }
        require(index >= 0) { "The demo asset was not found" }
        assets[index] = asset
        record(Change.Upsert(asset))
    }

    fun deleteAsset(assetId: String) {
        val removed = assets.removeAll { it.id == assetId }
        require(removed) { "The demo asset was not found" }
        record(Change.Delete(assetId))
        updateAlbumCounts()
    }

    private fun checkAvailable() {
        check(networkAvailable) { "The demo network is unavailable. Try again." }
        check(!sessionExpired) { "The demo session expired. Sign in again." }
    }

    private fun record(change: Change) {
        revision += 1
        changes += revision to change
        sortedAssetsCache = null
    }

    private fun sortedAssets(): List<PhotoAsset> = sortedAssetsCache
        ?: assets.sortedByDescending(PhotoAsset::capturedAtMillis).also { sortedAssetsCache = it }

    private fun updateAlbumCounts() {
        albums = albums.map { album ->
            album.copy(assetCount = assets.count { album.id in it.albumIds })
        }
    }

    private sealed interface Change {
        data class Upsert(val asset: PhotoAsset) : Change
        data class Delete(val assetId: String) : Change
    }

    companion object {
        private fun demoAssets(assetCount: Int): List<PhotoAsset> {
            val zone = ZoneId.systemDefault()
            fun day(year: Int, month: Int, day: Int, hour: Int = 12) =
                LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

            val samples = listOf(
                PhotoAsset("demo-01", "Morning on the coast", day(2026, 9, 21), MediaKind.Photo, 4_300_000, 4032, 3024, setOf("album-coast", "album-weekend"), artworkStyle = "coast"),
                PhotoAsset("demo-02", "Blue hour", day(2026, 9, 20, 19), MediaKind.Photo, 3_800_000, 3024, 4032, setOf("album-city", "album-favorites"), true, "blue-hour"),
                PhotoAsset("demo-03", "The long way home", day(2026, 9, 18), MediaKind.Video, 28_000_000, 3840, 2160, setOf("album-coast"), artworkStyle = "road"),
                PhotoAsset("demo-04", "Citrus market", day(2026, 9, 16), MediaKind.Photo, 5_100_000, 4032, 3024, setOf("album-coast", "album-weekend"), artworkStyle = "citrus"),
                PhotoAsset("demo-05", "Old town", day(2026, 9, 12), MediaKind.Photo, 4_900_000, 3024, 4032, setOf("album-city"), artworkStyle = "old-town"),
                PhotoAsset("demo-06", "Between trains", day(2026, 9, 9), MediaKind.Photo, 3_100_000, 4032, 3024, setOf("album-city"), artworkStyle = "train"),
                PhotoAsset("demo-07", "A little motion", day(2026, 9, 7), MediaKind.LivePhoto, 6_900_000, 3024, 4032, setOf("album-city", "album-favorites"), true, "garden", liveMotionResourceKey = "resOriginalVidComplRes"),
                PhotoAsset("demo-08", "Late afternoon", day(2026, 9, 4), MediaKind.Photo, 4_700_000, 4032, 3024, setOf("album-city"), artworkStyle = "afternoon"),
                PhotoAsset("demo-09", "Salt air", day(2026, 8, 28), MediaKind.Photo, 5_500_000, 4032, 3024, setOf("album-weekend"), artworkStyle = "salt-air"),
                PhotoAsset("demo-10", "Small things", day(2026, 8, 25), MediaKind.Raw, 22_000_000, 6048, 4024, setOf("album-weekend"), artworkStyle = "still-life"),
                PhotoAsset("demo-11", "First light", day(2026, 8, 20), MediaKind.Photo, 4_100_000, 4032, 3024, setOf("album-weekend", "album-favorites"), true, "first-light"),
                PhotoAsset("demo-12", "Window seat", day(2026, 8, 12), MediaKind.Video, 41_000_000, 3840, 2160, setOf("album-weekend"), artworkStyle = "window"),
            )
            return when {
                assetCount <= 0 -> emptyList()
                assetCount <= samples.size -> samples.take(assetCount)
                else -> List(assetCount) { index ->
                    val sample = samples[index % samples.size]
                    sample.copy(
                        id = "demo-generated-%06d".format(index + 1),
                        title = "${sample.title} ${index + 1}",
                        capturedAtMillis = sample.capturedAtMillis - index,
                    )
                }
            }
        }
    }
}
