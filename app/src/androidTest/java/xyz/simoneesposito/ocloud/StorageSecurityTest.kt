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

package xyz.simoneesposito.ocloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import xyz.simoneesposito.ocloud.data.database.AlbumEntity
import xyz.simoneesposito.ocloud.data.database.AlbumMembershipEntity
import xyz.simoneesposito.ocloud.data.database.CloudAssetEntity
import xyz.simoneesposito.ocloud.data.database.OCloudDatabase
import xyz.simoneesposito.ocloud.data.security.SecureSessionStorage

@RunWith(AndroidJUnit4::class)
class StorageSecurityTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OCloudDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun sessionIsEncryptedAtRestAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = SecureSessionStorage(context)
        val appleId = "private@example.test"
        val secret = "session-cookie-super-secret"

        storage.clear()
        storage.save(appleId, secret)
        val file = File(context.noBackupFilesDir, "icloud-session.enc")
        val bytes = file.readBytes()

        assertFalse(String(bytes).contains(appleId))
        assertFalse(String(bytes).contains(secret))
        assertEquals(appleId, storage.load()?.appleId)
        assertEquals(secret, storage.load()?.sessionJson)
        storage.clear()
        assertNull(storage.load())
    }

    @Test
    fun roomPersistsStableAssetsAndAlbumMembershipSeparately() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, OCloudDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val asset = CloudAssetEntity(
                assetId = "cloud-record-1", title = "original.heic", capturedAtMillis = 100,
                mediaKind = "Photo", sizeBytes = 1024, width = 4032, height = 3024,
                albumId = null, isFavorite = true, artworkStyle = "", resourceKey = "resOriginalRes",
            )
            database.albumDao().upsertAll(listOf(AlbumEntity("album-a", "Trip", 1, asset.assetId)))
            database.cloudAssetDao().upsertAll(listOf(asset))
            database.albumDao().upsertMemberships(listOf(AlbumMembershipEntity("album-a", asset.assetId)))

            assertEquals(1, database.cloudAssetDao().countForAlbum("album-a"))
            database.cloudAssetDao().markDownloaded(asset.assetId)
            assertEquals(true, database.cloudAssetDao().get(asset.assetId)?.isDownloaded)
            assertArrayEquals(arrayOf(asset.assetId), database.cloudAssetDao().activeIds().toTypedArray())
            database.clearAccountData()
            assertEquals(0, database.cloudAssetDao().count())
        } finally {
            database.close()
        }
    }

    @Test
    fun schemaOneMigratesLivePhotoResourceColumn() {
        val databaseName = "ocloud-migration-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 1).close()
        migrationHelper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            OCloudDatabase.MIGRATION_1_2,
            OCloudDatabase.MIGRATION_2_3,
        ).use { database ->
            database.query("PRAGMA table_info(cloud_assets)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("liveMotionResourceKey" in names)
            }
            database.query("PRAGMA index_list(cloud_assets)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("index_cloud_assets_isDeleted_capturedAtMillis" in names)
                assertTrue("index_cloud_assets_isDownloaded_isDeleted_capturedAtMillis" in names)
            }
        }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun schemaThreeAddsDownloadFailureMessage() {
        val databaseName = "ocloud-download-migration-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 3).close()
        migrationHelper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            OCloudDatabase.MIGRATION_3_4,
        ).use { database ->
            database.query("PRAGMA table_info(download_tasks)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("lastErrorMessage" in names)
            }
        }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun schemaFourAddsPhotoMetadataColumns() {
        val databaseName = "ocloud-photo-metadata-migration-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 4).close()
        migrationHelper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            OCloudDatabase.MIGRATION_4_5,
        ).use { database ->
            database.query("PRAGMA table_info(cloud_assets)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("locationLatitude" in names)
                assertTrue("locationLongitude" in names)
                assertTrue("orientation" in names)
                assertTrue("durationMillis" in names)
            }
        }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }
}
