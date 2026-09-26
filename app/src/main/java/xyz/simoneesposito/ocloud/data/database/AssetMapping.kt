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

package xyz.simoneesposito.ocloud.data.database

import xyz.simoneesposito.ocloud.domain.model.MediaKind
import xyz.simoneesposito.ocloud.domain.model.PhotoAlbum
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import xyz.simoneesposito.ocloud.domain.model.requiresDeviceAuthentication

fun PhotoAsset.toEntity(isDownloaded: Boolean = false): CloudAssetEntity = CloudAssetEntity(
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
    isDownloaded = isDownloaded,
)

fun CloudAssetEntity.toDomain(): PhotoAsset = PhotoAsset(
    id = assetId,
    title = title,
    capturedAtMillis = capturedAtMillis,
    mediaKind = runCatching { MediaKind.valueOf(mediaKind) }.getOrDefault(MediaKind.Photo),
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    albumIds = albumId?.let(::setOf).orEmpty(),
    isFavorite = isFavorite,
    artworkStyle = artworkStyle,
    resourceKey = resourceKey,
    liveMotionResourceKey = liveMotionResourceKey,
    libraryId = libraryId,
    orientation = orientation,
    durationMillis = durationMillis,
)

fun PhotoAlbum.toEntity(): AlbumEntity {
    val protected = requiresDeviceAuthentication()
    return AlbumEntity(
    albumId = id,
    title = title,
    assetCount = assetCount,
    coverAssetId = if (protected) null else coverAssetId,
    libraryId = libraryId,
    requiresAuthentication = protected,
    )
}

fun AlbumEntity.toDomain(): PhotoAlbum {
    val album = PhotoAlbum(
        id = albumId,
        title = title,
        assetCount = assetCount,
        coverAssetId = if (requiresAuthentication) null else coverAssetId,
        libraryId = libraryId,
        requiresAuthentication = requiresAuthentication,
    )
    return if (album.requiresDeviceAuthentication()) album.copy(coverAssetId = null, requiresAuthentication = true) else album
}
