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

import android.app.Application
import icloudbridge.icloudbridge.Bridge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import xyz.simoneesposito.ocloud.data.auth.ICloudAuthManager
import xyz.simoneesposito.ocloud.data.database.OCloudDatabase
import xyz.simoneesposito.ocloud.data.provider.ICloudPhotosProvider
import xyz.simoneesposito.ocloud.data.security.SecureSessionStorage
import xyz.simoneesposito.ocloud.data.sync.LibrarySyncScheduler
import xyz.simoneesposito.ocloud.domain.auth.Authenticator
import xyz.simoneesposito.ocloud.domain.provider.PhotosProvider

open class OCloudApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var photosBridge: Deferred<Bridge>
    lateinit var database: OCloudDatabase
        private set
    lateinit var sessionStorage: SecureSessionStorage
        private set
    lateinit var authManager: Authenticator
        private set
    lateinit var photosProvider: PhotosProvider
        private set

    override fun onCreate() {
        super.onCreate()
        database = OCloudDatabase.get(this)
        sessionStorage = SecureSessionStorage(this)
        photosProvider = createPhotosProvider()
        authManager = createAuthManager()
        applicationScope.launch {
            LibrarySyncScheduler.configure(
                this@OCloudApplication,
                enabled = backgroundSyncEnabled,
            )
        }
    }

    protected open val backgroundSyncEnabled: Boolean
        get() = LibrarySyncScheduler.isEnabled(this)

    protected open fun createPhotosProvider(): PhotosProvider {
        photosBridge = applicationScope.async {
            Bridge(File(cacheDir, "rclone-photos").absolutePath)
        }
        return ICloudPhotosProvider(this, { photosBridge.await() }, database)
    }

    protected open fun createAuthManager(): Authenticator {
        val manager = ICloudAuthManager(this, sessionStorage, database) { photosBridge.await() }
        (photosProvider as? ICloudPhotosProvider)?.let { manager.onAccountChanged = it::clearSessionCache }
        return manager
    }
}
