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

package xyz.simoneesposito.ocloud.data.auth

import android.content.Context
import icloudbridge.icloudbridge.Bridge
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import xyz.simoneesposito.ocloud.data.database.OCloudDatabase
import xyz.simoneesposito.ocloud.data.download.DownloadQueue
import xyz.simoneesposito.ocloud.data.security.SecureSessionStorage
import xyz.simoneesposito.ocloud.R
import xyz.simoneesposito.ocloud.domain.auth.Authenticator
import xyz.simoneesposito.ocloud.domain.auth.AuthState
import xyz.simoneesposito.ocloud.domain.auth.TrustedPhone
import xyz.simoneesposito.ocloud.domain.model.ICloudSecurityMode
import androidx.core.content.edit

/** Real Apple authentication, SRP, 2FA and Photos PCS authorization coordinator. */
class ICloudAuthManager(
    context: Context,
    private val storage: SecureSessionStorage,
    private val database: OCloudDatabase,
    private val bridgeProvider: suspend () -> Bridge,
) : Authenticator {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    private val mutablePhones = MutableStateFlow<List<TrustedPhone>>(emptyList())
    private val mutableMessage = MutableStateFlow<String?>(null)

    override val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val trustedPhones: StateFlow<List<TrustedPhone>> = mutablePhones.asStateFlow()
    override val message: StateFlow<String?> = mutableMessage.asStateFlow()
    var onAccountChanged: () -> Unit = {}

    override suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        val saved = storage.load() ?: return@withContext false
        runCatching {
            val bridge = bridgeProvider()
            clearLocalAccountIfChanged(accountHash(saved.appleId))
            bridge.restoreSession(saved.appleId, saved.sessionJson)
            rememberAccount(accountHash(saved.appleId))
            mutableState.value = authenticatedState(bridge)
        }.onFailure {
            storage.clear()
            mutableState.value = AuthState.LoggedOut
        }.isSuccess
    }

    override suspend fun signIn(appleId: String, password: String) = withContext(Dispatchers.IO) {
        mutableMessage.value = null
        mutableState.value = AuthState.Authenticating
        try {
            val bridge = bridgeProvider()
            val accountHash = accountHash(appleId)
            when (bridge.beginAuthentication(appleId.trim(), password)) {
                "requires_two_factor" -> {
                    clearLocalAccountIfChanged(accountHash)
                    rememberAccount(accountHash)
                    persist(appleId, bridge)
                    mutableState.value = AuthState.RequiresTwoFactor
                    runCatching { readPhones(bridge.trustedPhones()) }
                        .onSuccess { mutablePhones.value = it }
                        .onFailure { mutableMessage.value = appContext.getString(R.string.code_required, appContext.getString(R.string.brand_apple)) }
                }
                "signed_in" -> {
                    clearLocalAccountIfChanged(accountHash)
                    rememberAccount(accountHash)
                    persist(appleId, bridge)
                    establishPhotosAccess(appleId, bridge)
                }
                else -> error("Apple returned an unknown authentication state")
            }
            Unit
        } catch (_: Exception) {
            mutableMessage.value = appContext.getString(R.string.authentication_failed, appContext.getString(R.string.brand_apple))
            mutableState.value = AuthState.Failed(xyz.simoneesposito.ocloud.domain.auth.AuthFailure.AuthenticationFailed)
        }
    }

    override suspend fun requestSms(phone: TrustedPhone) = withContext(Dispatchers.IO) {
        try {
            val bridge = bridgeProvider()
            bridge.requestSMSCode(phone.id.toLong(), phone.mode)
            mutableMessage.value = appContext.getString(R.string.sms_sent, appContext.getString(R.string.brand_apple), phone.display)
        } catch (_: Exception) {
            mutableMessage.value = appContext.getString(R.string.sms_send_failed, appContext.getString(R.string.brand_apple))
        }
    }

    override suspend fun verifyCode(code: String, smsPhone: TrustedPhone?) = withContext(Dispatchers.IO) {
        mutableMessage.value = null
        mutableState.value = AuthState.VerifyingTwoFactor
        try {
            val bridge = bridgeProvider()
            if (smsPhone == null) bridge.verifyTwoFactor(code.trim())
            else bridge.verifySMSCode(code.trim(), smsPhone.id.toLong(), smsPhone.mode)
            val saved = storage.load() ?: error("The encrypted session could not be restored")
            persist(saved.appleId, bridge)
            establishPhotosAccess(saved.appleId, bridge)
        } catch (_: Exception) {
            mutableMessage.value = appContext.getString(R.string.code_verification_failed, appContext.getString(R.string.brand_apple))
            mutableState.value = AuthState.Failed(xyz.simoneesposito.ocloud.domain.auth.AuthFailure.InvalidVerificationCode)
        }
    }

    override suspend fun retryPhotosApproval() = withContext(Dispatchers.IO) {
        val saved = storage.load()
        if (saved == null) {
            mutableState.value = AuthState.LoggedOut
            return@withContext
        }
        establishPhotosAccess(saved.appleId, bridgeProvider())
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        bridge.clearSession()
        storage.clear()
        mutablePhones.value = emptyList()
        mutableMessage.value = null
        mutableState.value = AuthState.LoggedOut
    }

    override suspend fun invalidateExpiredSession() = withContext(Dispatchers.IO) {
        val bridge = bridgeProvider()
        bridge.clearSession()
        storage.clear()
        mutablePhones.value = emptyList()
        mutableMessage.value = appContext.getString(R.string.session_expired, appContext.getString(R.string.brand_apple))
        mutableState.value = AuthState.Failed(xyz.simoneesposito.ocloud.domain.auth.AuthFailure.SessionExpired)
    }

    private fun establishPhotosAccess(appleId: String, bridge: Bridge) {
        mutableState.value = AuthState.EstablishingPCS
        try {
            bridge.establishPhotosAccess()
            persist(appleId, bridge)
            mutableState.value = authenticatedState(bridge)
        } catch (_: Exception) {
            // Preserve the post-2FA session. The user can retry Photos access
            // without re-entering a password or disabling account protections.
            runCatching { persist(appleId, bridge) }
            mutableMessage.value = appContext.getString(R.string.photos_authorization_failed, appContext.getString(R.string.brand_apple))
            mutableState.value = AuthState.Failed(xyz.simoneesposito.ocloud.domain.auth.AuthFailure.PcsUnavailable)
        }
    }

    private fun authenticatedState(bridge: Bridge) = AuthState.Authenticated(
        if (bridge.photosPCSRequired()) ICloudSecurityMode.PhotosPCSAuthorization
        else ICloudSecurityMode.Standard,
    )

    private fun persist(appleId: String, bridge: Bridge) {
        storage.save(appleId, bridge.exportSession())
    }

    private suspend fun clearLocalAccountIfChanged(nextHash: String) {
        val preferences = appContext.getSharedPreferences(ACCOUNT_PREFERENCES, Context.MODE_PRIVATE)
        val previousHash = preferences.getString(LAST_ACCOUNT_HASH, null)
        if (previousHash == null || previousHash == nextHash) return

        storage.clear()
        database.clearAccountData()
        File(appContext.cacheDir, "rclone-photos").deleteRecursively()
        androidx.work.WorkManager.getInstance(appContext).cancelAllWorkByTag(DownloadQueue.DOWNLOAD_WORK_TAG)
        onAccountChanged()
    }

    private fun rememberAccount(hash: String) {
        appContext.getSharedPreferences(ACCOUNT_PREFERENCES, Context.MODE_PRIVATE)
            .edit { putString(LAST_ACCOUNT_HASH, hash) }
    }

    private fun accountHash(appleId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(appleId.trim().lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun readPhones(json: String): List<TrustedPhone> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val phone = array.optJSONObject(index) ?: return@mapNotNull null
            val id = phone.optInt("id", -1)
            if (id < 0) null else TrustedPhone(
                id = id,
                display = phone.optString("obfuscatedNumber").ifBlank { phone.optString("numberWithDialCode") },
                mode = phone.optString("pushMode").ifBlank { "sms" },
            )
        }
    }

    private companion object {
        const val ACCOUNT_PREFERENCES = "ocloud_account_binding"
        const val LAST_ACCOUNT_HASH = "last_account_sha256"
    }
}
