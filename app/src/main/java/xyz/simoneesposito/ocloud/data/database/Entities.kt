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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "cloud_assets",
    indices = [
        Index("capturedAtMillis"),
        Index("albumId"),
        Index(value = ["isDeleted", "capturedAtMillis"]),
        Index(value = ["isDownloaded", "isDeleted", "capturedAtMillis"]),
    ],
)
data class CloudAssetEntity(
    @PrimaryKey val assetId: String,
    val title: String,
    val capturedAtMillis: Long,
    val mediaKind: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val albumId: String?,
    val isFavorite: Boolean,
    val artworkStyle: String,
    val resourceKey: String = "resOriginalRes",
    val liveMotionResourceKey: String? = null,
    val libraryId: String = "",
    val orientation: Int? = null,
    val durationMillis: Long? = null,
    val isDownloaded: Boolean = false,
    val isDeleted: Boolean = false,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val albumId: String,
    val title: String,
    val assetCount: Int,
    val coverAssetId: String?,
    @ColumnInfo(defaultValue = "''") val libraryId: String = "",
    @ColumnInfo(defaultValue = "1") val requiresAuthentication: Boolean = true,
)

@Entity(tableName = "album_memberships", primaryKeys = ["albumId", "assetId"], indices = [Index("assetId")])
data class AlbumMembershipEntity(
    val albumId: String,
    val assetId: String,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val libraryId: String,
    val opaqueToken: String?,
    val lastSuccessfulSyncMillis: Long?,
    val fullRefreshRequired: Boolean = false,
)

@Entity(tableName = "download_tasks", indices = [Index("status")])
data class DownloadTaskEntity(
    @PrimaryKey val taskId: String,
    val cloudAssetId: String,
    val targetUri: String?,
    val status: String,
    val retryCount: Int = 0,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
)

/** Token material is kept in an Android Keystore encrypted file, never in Room. */
@Entity(tableName = "auth_session_metadata")
data class AuthSessionMetadataEntity(
    @PrimaryKey val sessionId: String,
    val securityMode: String,
    val state: String,
    val expiresAtMillis: Long?,
)
