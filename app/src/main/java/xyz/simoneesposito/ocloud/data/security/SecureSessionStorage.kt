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

package xyz.simoneesposito.ocloud.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedICloudSession(val appleId: String, val sessionJson: String)

/** AES-GCM session file encrypted with a non-exportable Android Keystore key. */
class SecureSessionStorage(context: Context) {
    private val file = File(context.noBackupFilesDir, "icloud-session.enc")
    private val keyAlias = "ocloud.icloud.session.aes.v1"

    fun save(appleId: String, sessionJson: String) {
        require(appleId.isNotBlank() && sessionJson.isNotBlank())
        val plaintext = JSONObject()
            .put("appleId", appleId)
            .put("sessionJson", sessionJson)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        val output = byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(output)
            stream.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            temporary.delete()
            error("Could not replace the encrypted iPhotos session")
        }
        check(temporary.renameTo(file)) { "Could not save the encrypted iPhotos session" }
    }

    fun load(): SavedICloudSession? {
        if (!file.exists()) return null
        return runCatching {
            val data = FileInputStream(file).use { it.readBytes() }
            require(data.size > HEADER_LENGTH && data[0] == FORMAT_VERSION)
            val ivLength = data[1].toInt() and 0xff
            require(ivLength in 12..16 && data.size > 2 + ivLength)
            val iv = data.copyOfRange(2, 2 + ivLength)
            val ciphertext = data.copyOfRange(2 + ivLength, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val payload = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            SavedICloudSession(payload.getString("appleId"), payload.getString("sessionJson"))
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_LENGTH = 2 + 12
    }
}
