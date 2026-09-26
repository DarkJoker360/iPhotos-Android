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

package xyz.simoneesposito.ocloud.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.simoneesposito.ocloud.OCloudApplication
import xyz.simoneesposito.ocloud.data.database.DownloadTaskEntity
import xyz.simoneesposito.ocloud.data.database.toDomain
import xyz.simoneesposito.ocloud.data.repository.LibraryRepository
import xyz.simoneesposito.ocloud.data.repository.PhotoDownloadRepository
import xyz.simoneesposito.ocloud.domain.auth.AuthState
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import androidx.core.net.toUri

/** Durable, idempotent download queue. WorkManager retries after process death or connectivity loss. */
class DownloadQueue(private val context: Context) {
    private val app = context.applicationContext as OCloudApplication
    private val dao = app.database.downloadTaskDao()

    suspend fun enqueue(asset: PhotoAsset): Boolean = withContext(Dispatchers.IO) {
        if (app.database.cloudAssetDao().get(asset.id)?.isDownloaded == true) return@withContext false
        val taskId = stableTaskId(asset.id)
        val current = dao.getForAsset(asset.id)
        if (current?.status in setOf("queued", "downloading", "retrying")) return@withContext false
        val task = DownloadTaskEntity(
            taskId = taskId,
            cloudAssetId = asset.id,
            targetUri = current?.targetUri,
            status = "queued",
            retryCount = current?.retryCount ?: 0,
        )
        dao.upsert(task)
        enqueueWork(task)
        true
    }

    suspend fun retryPending(): Int = withContext(Dispatchers.IO) {
        val retryable = dao.retryable()
        retryable.forEach { task ->
            val queued = task.copy(status = "queued", lastErrorCode = null, lastErrorMessage = null)
            dao.upsert(queued)
            enqueueWork(queued, ExistingWorkPolicy.REPLACE)
        }
        retryable.size
    }

    private fun enqueueWork(task: DownloadTaskEntity, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<PhotoDownloadWorker>()
            .setInputData(Data.Builder().putString(KEY_TASK_ID, task.taskId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DOWNLOAD_WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("icloud-download-${task.taskId}", policy, request)
    }

    private fun stableTaskId(assetId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(assetId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY_TASK_ID = "download_task_id"
        const val DOWNLOAD_WORK_TAG = "ocloud-download-task"
    }
}

class PhotoDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OCloudApplication
        val dao = app.database.downloadTaskDao()
        val taskId = inputData.getString(DownloadQueue.KEY_TASK_ID) ?: return Result.failure()
        val current = dao.get(taskId) ?: return Result.success()
        val assetRow = app.database.cloudAssetDao().get(current.cloudAssetId)
            ?: return fail(dao, current, "asset_missing", "This photo is no longer in the local library.")

        if (app.authManager.state.value !is AuthState.Authenticated && !app.authManager.restoreSession()) {
            dao.upsert(current.copy(status = "paused_auth", lastErrorCode = "auth_required", lastErrorMessage = "Sign in again to resume this download."))
            return Result.success()
        }

        removePendingRows(current.targetUri)
        var working = current.copy(status = "downloading", targetUri = null, lastErrorCode = null, lastErrorMessage = null)
        dao.upsert(working)
        return try {
            val downloads = PhotoDownloadRepository(
                applicationContext,
                app.photosProvider,
                LibraryRepository(app.database, app.photosProvider),
            )
            downloads.download(assetRow.toDomain()) { uris ->
                working = working.copy(targetUri = uris.joinToString("\n"))
                dao.upsert(working)
            }
            dao.upsert(working.copy(status = "succeeded", targetUri = null, lastErrorMessage = null))
            Result.success()
        } catch (failure: Exception) {
            val message = failure.message ?: "The media could not be downloaded."
            if (message.contains("session expired", ignoreCase = true) || message.contains("sign in again", ignoreCase = true)) {
                dao.upsert(working.copy(status = "paused_auth", lastErrorCode = "auth_required", lastErrorMessage = "Your Apple session expired. Sign in again to resume this download."))
                Result.success()
            } else {
                val retries = working.retryCount + 1
                val retrying = runAttemptCount < MAX_ATTEMPTS
                dao.upsert(
                    working.copy(
                        status = if (retrying) "retrying" else "failed",
                        retryCount = retries,
                        lastErrorCode = "download_failed",
                        lastErrorMessage = message,
                    ),
                )
                if (retrying) Result.retry() else Result.failure()
            }
        }
    }

    private fun removePendingRows(encodedUris: String?) {
        encodedUris.orEmpty().split('\n').filter(String::isNotBlank).forEach { value ->
            runCatching { applicationContext.contentResolver.delete(value.toUri(), null, null) }
        }
    }

    private suspend fun fail(
        dao: xyz.simoneesposito.ocloud.data.database.DownloadTaskDao,
        task: DownloadTaskEntity,
        code: String,
        message: String,
    ): Result {
        dao.upsert(task.copy(status = "failed", lastErrorCode = code, lastErrorMessage = message))
        return Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 7
    }
}
