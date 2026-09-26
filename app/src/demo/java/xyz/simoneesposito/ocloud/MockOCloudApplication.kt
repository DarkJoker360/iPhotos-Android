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

import androidx.core.content.edit
import xyz.simoneesposito.ocloud.data.auth.MockAuthManager
import xyz.simoneesposito.ocloud.data.provider.MockPhotosProvider
import xyz.simoneesposito.ocloud.data.sync.LibrarySyncScheduler
import xyz.simoneesposito.ocloud.domain.auth.Authenticator
import xyz.simoneesposito.ocloud.domain.provider.PhotosProvider

/** Starts directly in an offline sample library. This application is only packaged in the demo flavor. */
class MockOCloudApplication : OCloudApplication() {
    private val demoThumbnails: Map<String, ByteArray> by lazy {
        mapOf(
            "demo-01" to readDemoImage("amalfi-coast.jpg"),
            "demo-02" to readDemoImage("rome-blue-hour.jpg"),
            "demo-03" to readDemoImage("old-town-friends.jpg"),
            "demo-04" to readDemoImage("market-citrus.jpg"),
            "demo-05" to readDemoImage("old-town-friends.jpg"),
            "demo-06" to readDemoImage("amalfi-coast.jpg"),
            "demo-07" to readDemoImage("rome-blue-hour.jpg"),
            "demo-08" to readDemoImage("old-town-friends.jpg"),
            "demo-09" to readDemoImage("amalfi-coast.jpg"),
            "demo-10" to readDemoImage("market-citrus.jpg"),
            "demo-11" to readDemoImage("rome-blue-hour.jpg"),
            "demo-12" to readDemoImage("old-town-friends.jpg"),
        )
    }

    override val backgroundSyncEnabled: Boolean = false

    override fun createPhotosProvider(): PhotosProvider = MockPhotosProvider(
        thumbnailBytesByAssetId = demoThumbnails,
    )

    override fun createAuthManager(): Authenticator = MockAuthManager(
        twoFactorRequired = false,
        authenticatedOnStart = true,
    )

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences(LibrarySyncScheduler.PREFERENCES, MODE_PRIVATE).edit {
            putBoolean("onboarding_complete", true)
            putBoolean(LibrarySyncScheduler.KEY_AUTO_SYNC_ENABLED, false)
        }
    }

    private fun readDemoImage(fileName: String): ByteArray =
        assets.open("demo_photos/$fileName").use { it.readBytes() }
}
