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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.simoneesposito.ocloud.domain.auth.AuthFailure
import xyz.simoneesposito.ocloud.domain.auth.AuthState
import xyz.simoneesposito.ocloud.domain.auth.Authenticator
import xyz.simoneesposito.ocloud.domain.auth.TrustedPhone
import xyz.simoneesposito.ocloud.domain.model.ICloudSecurityMode

/** Deterministic authentication state machine for UI and repository tests. */
class MockAuthManager(
    private val securityMode: ICloudSecurityMode = ICloudSecurityMode.Standard,
    private val twoFactorRequired: Boolean = true,
    private val approvalRequired: Boolean = securityMode == ICloudSecurityMode.PhotosPCSAuthorization,
    authenticatedOnStart: Boolean = false,
) : Authenticator {
    private val mutableState = MutableStateFlow<AuthState>(
        if (authenticatedOnStart) AuthState.Authenticated(securityMode) else AuthState.LoggedOut,
    )
    private val mutablePhones = MutableStateFlow(listOf(TrustedPhone(1, "+1 ••• ••• 0184", "sms")))
    private val mutableMessage = MutableStateFlow<String?>(null)

    override val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val trustedPhones: StateFlow<List<TrustedPhone>> = mutablePhones.asStateFlow()
    override val message: StateFlow<String?> = mutableMessage.asStateFlow()

    private var signedIn = authenticatedOnStart

    override suspend fun restoreSession(): Boolean = signedIn

    override suspend fun signIn(appleId: String, password: String) {
        mutableMessage.value = null
        mutableState.value = AuthState.Authenticating
        if (!appleId.contains('@') || password.isBlank()) {
            mutableState.value = AuthState.Failed(AuthFailure.AuthenticationFailed)
            mutableMessage.value = "Enter a demo iAccount and password."
            return
        }
        signedIn = true
        continueAfterCredentials()
    }

    override suspend fun verifyCode(code: String, smsPhone: TrustedPhone?) {
        if (mutableState.value != AuthState.RequiresTwoFactor &&
            mutableState.value != AuthState.Failed(AuthFailure.InvalidVerificationCode)
        ) return
        mutableState.value = AuthState.VerifyingTwoFactor
        if (code.trim() != "123456") {
            mutableState.value = AuthState.Failed(AuthFailure.InvalidVerificationCode)
            mutableMessage.value = "The demo verification code is 123456."
            return
        }
        mutableMessage.value = if (smsPhone == null) "Trusted-device code accepted in the demo." else "Demo SMS code accepted."
        continueAfterCredentials()
    }

    override suspend fun requestSms(phone: TrustedPhone) {
        if (phone !in mutablePhones.value) return
        mutableMessage.value = "A demo verification code was sent to ${phone.display}. Use 123456."
    }

    override suspend fun retryPhotosApproval() {
        if (!signedIn) {
            mutableState.value = AuthState.LoggedOut
            return
        }
        if (approvalRequired) {
            mutableMessage.value = "Approve iPhotos in the demo to continue."
            mutableState.value = AuthState.RequiresTrustedDeviceApproval
        } else establishPhotosAccess()
    }

    fun approveTrustedDevice() {
        if (mutableState.value != AuthState.RequiresTrustedDeviceApproval) return
        mutableMessage.value = "Demo trusted-device approval accepted."
        establishPhotosAccess()
    }

    fun expireSession() {
        signedIn = false
        mutableState.value = AuthState.Failed(AuthFailure.SessionExpired)
        mutableMessage.value = "The demo session expired. Sign in again."
    }

    override suspend fun invalidateExpiredSession() = expireSession()

    override suspend fun logout() {
        signedIn = false
        mutableMessage.value = null
        mutableState.value = AuthState.LoggedOut
    }

    private fun continueAfterCredentials() {
        when {
            twoFactorRequired && mutableState.value != AuthState.VerifyingTwoFactor -> mutableState.value = AuthState.RequiresTwoFactor
            approvalRequired -> {
                mutableState.value = AuthState.RequiresTrustedDeviceApproval
                mutableMessage.value = "Approve Photos access on a trusted device in this demo."
            }
            securityMode == ICloudSecurityMode.PhotosPCSAuthorization -> establishPhotosAccess()
            else -> mutableState.value = AuthState.Authenticated(ICloudSecurityMode.Standard)
        }
    }

    private fun establishPhotosAccess() {
        mutableState.value = AuthState.EstablishingPCS
        mutableState.value = AuthState.Authenticated(securityMode)
    }
}
