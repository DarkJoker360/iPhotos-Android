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

package xyz.simoneesposito.ocloud.domain.auth

import kotlinx.coroutines.flow.StateFlow

data class TrustedPhone(val id: Int, val display: String, val mode: String)

/** Authentication state and actions shared by Apple and deterministic test implementations. */
interface Authenticator {
    val state: StateFlow<AuthState>
    val trustedPhones: StateFlow<List<TrustedPhone>>
    val message: StateFlow<String?>

    suspend fun restoreSession(): Boolean
    suspend fun signIn(appleId: String, password: String)
    suspend fun verifyCode(code: String, smsPhone: TrustedPhone? = null)
    suspend fun requestSms(phone: TrustedPhone)
    suspend fun retryPhotosApproval()
    suspend fun invalidateExpiredSession()
    suspend fun logout()
}
