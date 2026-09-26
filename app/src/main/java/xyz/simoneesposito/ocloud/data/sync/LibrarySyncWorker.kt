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

package xyz.simoneesposito.ocloud.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import xyz.simoneesposito.ocloud.OCloudApplication
import xyz.simoneesposito.ocloud.data.repository.LibraryRepository
import xyz.simoneesposito.ocloud.domain.auth.AuthState
import androidx.core.content.edit

/** Periodic, network-constrained refresh; Room keeps the last good library available offline. */
class LibrarySyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OCloudApplication
        if (!LibrarySyncScheduler.isEnabled(applicationContext)) return Result.success()

        val authenticated = app.authManager.state.value is AuthState.Authenticated || app.authManager.restoreSession()
        if (!authenticated) return Result.success()

        return try {
            LibraryRepository(app.database, app.photosProvider).sync()
            Result.success()
        } catch (failure: Exception) {
            val message = failure.message.orEmpty()
            if (message.contains("session expired", ignoreCase = true) || message.contains("sign in again", ignoreCase = true)) {
                app.authManager.invalidateExpiredSession()
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}

object LibrarySyncScheduler {
    const val WORK_NAME = "ocloud-library-sync"
    const val PREFERENCES = "ocloud_preferences"
    const val KEY_AUTO_SYNC_ENABLED = "automatic_sync_enabled"

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_SYNC_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_AUTO_SYNC_ENABLED, enabled)
            }
        configure(context, enabled)
    }

    fun configure(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
