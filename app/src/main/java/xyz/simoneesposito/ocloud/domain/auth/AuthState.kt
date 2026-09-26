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

import xyz.simoneesposito.ocloud.domain.model.ICloudSecurityMode

sealed interface AuthState {
    data object LoggedOut : AuthState
    data object Authenticating : AuthState
    data object RequiresTwoFactor : AuthState
    data object VerifyingTwoFactor : AuthState
    data object RequiresTrustedDeviceApproval : AuthState
    data object EstablishingPCS : AuthState
    data class Authenticated(val securityMode: ICloudSecurityMode) : AuthState
    data class Failed(val reason: AuthFailure) : AuthState
}

enum class AuthFailure {
    InvalidVerificationCode,
    PcsUnavailable,
    SessionExpired,
    AuthenticationFailed,
}
