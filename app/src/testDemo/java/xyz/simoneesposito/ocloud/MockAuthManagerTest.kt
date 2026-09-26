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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.simoneesposito.ocloud.data.auth.MockAuthManager
import xyz.simoneesposito.ocloud.domain.auth.AuthFailure
import xyz.simoneesposito.ocloud.domain.auth.AuthState
import xyz.simoneesposito.ocloud.domain.model.ICloudSecurityMode

class MockAuthManagerTest {
    @Test
    fun standardAccountCanSignInAfterSecondFactor() = runBlocking {
        val auth = MockAuthManager()
        auth.signIn("photos@example.test", "test-password")
        assertEquals(AuthState.RequiresTwoFactor, auth.state.value)

        auth.verifyCode("123456")
        assertEquals(AuthState.Authenticated(ICloudSecurityMode.Standard), auth.state.value)
        assertTrue(auth.restoreSession())
        auth.logout()
        assertFalse(auth.restoreSession())
    }

    @Test
    fun encryptedPhotosFlowRequiresTrustedDeviceApprovalAndKeepsSecurityMode() = runBlocking {
        val auth = MockAuthManager(ICloudSecurityMode.PhotosPCSAuthorization)
        auth.signIn("photos@example.test", "test-password")
        auth.verifyCode("123456")
        assertEquals(AuthState.RequiresTrustedDeviceApproval, auth.state.value)

        auth.approveTrustedDevice()
        assertEquals(AuthState.Authenticated(ICloudSecurityMode.PhotosPCSAuthorization), auth.state.value)
    }

    @Test
    fun invalidCodeIsActionableAndCanBeRetried() = runBlocking {
        val auth = MockAuthManager()
        auth.signIn("photos@example.test", "test-password")
        auth.verifyCode("000000")
        assertEquals(AuthState.Failed(AuthFailure.InvalidVerificationCode), auth.state.value)
        assertTrue(auth.message.value.orEmpty().contains("123456"))

        auth.verifyCode("123456")
        assertEquals(AuthState.Authenticated(ICloudSecurityMode.Standard), auth.state.value)
    }
}
