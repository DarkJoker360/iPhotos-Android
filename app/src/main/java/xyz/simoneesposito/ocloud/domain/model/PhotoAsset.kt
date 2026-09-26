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

package xyz.simoneesposito.ocloud.domain.model

enum class MediaKind { Photo, Video, Raw, LivePhoto }
enum class ICloudSecurityMode { Standard, PhotosPCSAuthorization }

data class PhotoAsset(
    val id: String,
    val title: String,
    val capturedAtMillis: Long,
    val mediaKind: MediaKind,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val albumIds: Set<String> = emptySet(),
    val isFavorite: Boolean = false,
    val artworkStyle: String = "coast",
    val resourceKey: String = "resOriginalRes",
    val liveMotionResourceKey: String? = null,
    val libraryId: String = "",
    val orientation: Int? = null,
    val durationMillis: Long? = null,
    /** Short-lived Apple CDN URL kept in memory only; never written to Room. */
    val thumbnailDownloadUrl: String? = null,
)

data class PhotoPage(
    val assets: List<PhotoAsset>,
    val nextCursor: String?,
)

data class PhotoAlbum(
    val id: String,
    val title: String,
    val assetCount: Int,
    val coverAssetId: String?,
    val libraryId: String = "",
    val requiresAuthentication: Boolean = false,
)

/** Private smart albums stay protected even when older cached rows lack the flag. */
fun PhotoAlbum.requiresDeviceAuthentication(): Boolean =
    requiresAuthentication || title.equals("Hidden", ignoreCase = true) ||
        title.equals("Recently Deleted", ignoreCase = true)

data class UploadAsset(
    val contentUri: String,
    val mediaStoreId: Long?,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val sha256: String? = null,
)

data class UploadResult(
    val assetId: String?,
    val acceptedByApple: Boolean,
    val duplicate: Boolean = false,
    val simulated: Boolean = false,
)

data class SyncState(val token: String?)

data class SyncResult(
    val changedAssets: List<PhotoAsset>,
    val deletedAssetIds: Set<String>,
    val nextState: SyncState,
    val fullRefreshRequired: Boolean = false,
    /** True only when changedAssets is a complete snapshot of the library. */
    val fullSnapshot: Boolean = false,
)

/** A stable upload key that never relies on a filename by itself. */
fun UploadAsset.deduplicationKey(): String = when {
    !sha256.isNullOrBlank() -> "sha256:$sha256"
    mediaStoreId != null -> "media:$mediaStoreId:$sizeBytes:$modifiedAtMillis"
    else -> "uri:$contentUri:$sizeBytes:$modifiedAtMillis"
}
