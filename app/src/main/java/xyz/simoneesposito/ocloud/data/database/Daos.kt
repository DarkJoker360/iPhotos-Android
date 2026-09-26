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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudAssetDao {
    @Query("SELECT * FROM cloud_assets WHERE isDeleted = 0 ORDER BY capturedAtMillis DESC LIMIT :limit")
    fun observeLatest(limit: Int = 200): Flow<List<CloudAssetEntity>>

    @Query("SELECT * FROM cloud_assets WHERE assetId = :assetId LIMIT 1")
    suspend fun get(assetId: String): CloudAssetEntity?

    @Query("SELECT * FROM cloud_assets WHERE assetId = :assetId AND isDeleted = 0 LIMIT 1")
    suspend fun getActive(assetId: String): CloudAssetEntity?

    @Query("SELECT COUNT(*) FROM cloud_assets WHERE isDeleted = 0")
    suspend fun count(): Int

    @Query("SELECT * FROM cloud_assets WHERE isDeleted = 0 ORDER BY capturedAtMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun page(offset: Int, limit: Int): List<CloudAssetEntity>

    @Query("SELECT * FROM cloud_assets WHERE isDownloaded = 1 AND isDeleted = 0 ORDER BY capturedAtMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun downloadedPage(offset: Int, limit: Int): List<CloudAssetEntity>

    @Query("SELECT COUNT(*) FROM cloud_assets WHERE isDownloaded = 1 AND isDeleted = 0")
    suspend fun downloadedCount(): Int

    @Query("SELECT cloud_assets.* FROM cloud_assets INNER JOIN album_memberships ON cloud_assets.assetId = album_memberships.assetId WHERE album_memberships.albumId = :albumId AND cloud_assets.isDeleted = 0 ORDER BY cloud_assets.capturedAtMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun pageForAlbum(albumId: String, offset: Int, limit: Int): List<CloudAssetEntity>

    @Query("SELECT COUNT(*) FROM cloud_assets INNER JOIN album_memberships ON cloud_assets.assetId = album_memberships.assetId WHERE album_memberships.albumId = :albumId AND cloud_assets.isDeleted = 0")
    suspend fun countForAlbum(albumId: String): Int

    @Query("SELECT assetId FROM cloud_assets WHERE isDeleted = 0")
    suspend fun activeIds(): List<String>

    @Query("SELECT * FROM cloud_assets WHERE isDeleted = 0")
    suspend fun activeAssets(): List<CloudAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<CloudAssetEntity>)

    @Query("UPDATE cloud_assets SET isDeleted = 1 WHERE assetId IN (:assetIds)")
    suspend fun markDeleted(assetIds: List<String>)

    @Query("UPDATE cloud_assets SET isDeleted = 1 WHERE isDownloaded = 0")
    suspend fun invalidateUndownloadedRemoteCache()

    @Query("UPDATE cloud_assets SET isDownloaded = 1 WHERE assetId = :assetId")
    suspend fun markDownloaded(assetId: String)

    @Query("DELETE FROM cloud_assets")
    suspend fun clear()
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums")
    suspend fun getAllOnce(): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemberships(memberships: List<AlbumMembershipEntity>)

    @Query("UPDATE albums SET coverAssetId = :assetId WHERE albumId = :albumId")
    suspend fun setCoverAssetId(albumId: String, assetId: String)

    @Query("UPDATE albums SET coverAssetId = NULL WHERE albumId = :albumId")
    suspend fun clearCoverAssetId(albumId: String)

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM album_memberships")
    suspend fun clearMemberships()
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE libraryId = :libraryId LIMIT 1")
    suspend fun get(libraryId: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_state WHERE libraryId LIKE 'icloud-photos-all-page-cursor%' OR libraryId LIKE 'icloud-photos-album-page%')")
    suspend fun hasLegacyPhotoPageCursors(): Boolean

    @Query("DELETE FROM sync_state WHERE libraryId LIKE 'icloud-photos-all-page-cursor%' OR libraryId LIKE 'icloud-photos-album-page%'")
    suspend fun clearLegacyPhotoPageCursors()

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY taskId")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE cloudAssetId = :assetId LIMIT 1")
    suspend fun getForAsset(assetId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun get(taskId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status IN ('failed', 'paused_auth', 'retrying')")
    suspend fun retryable(): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks")
    suspend fun clear()
}

@Dao
interface AuthSessionMetadataDao {
    @Query("SELECT * FROM auth_session_metadata ORDER BY sessionId LIMIT 1")
    suspend fun getCurrent(): AuthSessionMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: AuthSessionMetadataEntity)

    @Query("DELETE FROM auth_session_metadata")
    suspend fun clear()
}
