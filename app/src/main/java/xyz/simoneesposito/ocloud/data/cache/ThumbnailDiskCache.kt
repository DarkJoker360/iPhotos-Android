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

package xyz.simoneesposito.ocloud.data.cache

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persistent, app-private thumbnail cache with a byte-bounded least-recently-used eviction policy. */
class ThumbnailDiskCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val directory = File(context.applicationContext.filesDir, "photo-thumbnails-v1")
    private val mutex = Mutex()
    private var cachedBytes: Long? = null

    suspend fun get(assetId: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(assetId)
            if (!file.isFile) return@withLock null
            if (file.length() !in 1..MAX_ENTRY_BYTES) {
                val removedSize = file.length()
                file.delete()
                cachedBytes = cachedBytes?.minus(removedSize)?.coerceAtLeast(0L)
                return@withLock null
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                val removedSize = file.length()
                file.delete()
                cachedBytes = cachedBytes?.minus(removedSize)?.coerceAtLeast(0L)
                null
            } else {
                file.setLastModified(System.currentTimeMillis())
                bytes
            }
        }
    }

    suspend fun put(assetId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_ENTRY_BYTES) return@withContext
        mutex.withLock {
            if (!directory.exists() && !directory.mkdirs()) return@withLock
            val currentBytes = currentBytes()
            val target = fileFor(assetId)
            val previousSize = target.takeIf(File::exists)?.length() ?: 0L
            val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (!temporary.renameTo(target)) return@withLock
                target.setLastModified(System.currentTimeMillis())
                cachedBytes = (currentBytes - previousSize + bytes.size).coerceAtLeast(0L)
                trimToLimit(target)
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.forEach(File::delete)
            cachedBytes = 0L
        }
    }

    suspend fun remove(assetId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(assetId)
            val previousSize = file.takeIf(File::exists)?.length() ?: 0L
            file.delete()
            cachedBytes = cachedBytes?.minus(previousSize)?.coerceAtLeast(0L)
        }
    }

    private fun currentBytes(): Long = cachedBytes ?: directory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && !it.name.endsWith(".tmp") }
        .sumOf(File::length)
        .also { cachedBytes = it }

    private fun trimToLimit(protectedFile: File) {
        var size = cachedBytes ?: return
        if (size <= maxBytes) return
        val files = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
        for (file in files.sortedBy(File::lastModified)) {
            if (size <= maxBytes) break
            if (file == protectedFile) continue
            val fileSize = file.length()
            if (file.delete()) size = (size - fileSize).coerceAtLeast(0L)
        }
        cachedBytes = size
    }

    private fun fileFor(assetId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(assetId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, "$name.thumb")
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 384L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 12L * 1024L * 1024L
    }
}
