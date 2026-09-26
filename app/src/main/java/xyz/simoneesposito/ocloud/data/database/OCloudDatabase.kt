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

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        CloudAssetEntity::class,
        AlbumEntity::class,
        AlbumMembershipEntity::class,
        SyncStateEntity::class,
        DownloadTaskEntity::class,
        AuthSessionMetadataEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class OCloudDatabase : RoomDatabase() {
    abstract fun cloudAssetDao(): CloudAssetDao
    abstract fun albumDao(): AlbumDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun authSessionMetadataDao(): AuthSessionMetadataDao

    suspend fun clearAccountData() = withTransaction {
        albumDao().clearMemberships()
        albumDao().clearAlbums()
        cloudAssetDao().clear()
        syncStateDao().clear()
        downloadTaskDao().clear()
        authSessionMetadataDao().clear()
    }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_assets ADD COLUMN liveMotionResourceKey TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_assets_isDeleted_capturedAtMillis ON cloud_assets (isDeleted, capturedAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_assets_isDownloaded_isDeleted_capturedAtMillis ON cloud_assets (isDownloaded, isDeleted, capturedAtMillis)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN lastErrorMessage TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_assets ADD COLUMN locationLatitude REAL")
                db.execSQL("ALTER TABLE cloud_assets ADD COLUMN locationLongitude REAL")
                db.execSQL("ALTER TABLE cloud_assets ADD COLUMN orientation INTEGER")
                db.execSQL("ALTER TABLE cloud_assets ADD COLUMN durationMillis INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE albums ADD COLUMN libraryId TEXT NOT NULL DEFAULT ''")
                // Existing rows have no privacy metadata. Treat them as private until Apple refreshes the snapshot.
                db.execSQL("ALTER TABLE albums ADD COLUMN requiresAuthentication INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS upload_tasks")
                db.execSQL("DROP TABLE IF EXISTS local_assets")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE cloud_assets_new (
                        assetId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        capturedAtMillis INTEGER NOT NULL,
                        mediaKind TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        albumId TEXT,
                        isFavorite INTEGER NOT NULL,
                        artworkStyle TEXT NOT NULL,
                        resourceKey TEXT NOT NULL,
                        liveMotionResourceKey TEXT,
                        libraryId TEXT NOT NULL,
                        orientation INTEGER,
                        durationMillis INTEGER,
                        isDownloaded INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL
                    )""",
                )
                db.execSQL(
                    """INSERT INTO cloud_assets_new (
                        assetId, title, capturedAtMillis, mediaKind, sizeBytes, width, height,
                        albumId, isFavorite, artworkStyle, resourceKey, liveMotionResourceKey,
                        libraryId, orientation, durationMillis, isDownloaded, isDeleted
                    ) SELECT
                        assetId, title, capturedAtMillis, mediaKind, sizeBytes, width, height,
                        albumId, isFavorite, artworkStyle, resourceKey, liveMotionResourceKey,
                        libraryId, orientation, durationMillis, isDownloaded, isDeleted
                    FROM cloud_assets""",
                )
                db.execSQL("DROP TABLE cloud_assets")
                db.execSQL("ALTER TABLE cloud_assets_new RENAME TO cloud_assets")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_assets_capturedAtMillis ON cloud_assets (capturedAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_assets_albumId ON cloud_assets (albumId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_assets_isDeleted_capturedAtMillis ON cloud_assets (isDeleted, capturedAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_assets_isDownloaded_isDeleted_capturedAtMillis ON cloud_assets (isDownloaded, isDeleted, capturedAtMillis)")
            }
        }

        @Volatile private var instance: OCloudDatabase? = null

        fun get(context: Context): OCloudDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OCloudDatabase::class.java,
                "ocloud.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
        }
    }
}
