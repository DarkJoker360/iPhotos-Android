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

import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import xyz.simoneesposito.ocloud.domain.auth.AuthFailure
import xyz.simoneesposito.ocloud.domain.auth.AuthState
import xyz.simoneesposito.ocloud.domain.auth.TrustedPhone
import xyz.simoneesposito.ocloud.domain.model.MediaKind
import xyz.simoneesposito.ocloud.domain.model.PhotoAlbum
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import xyz.simoneesposito.ocloud.domain.model.requiresDeviceAuthentication
import xyz.simoneesposito.ocloud.ui.AppTab
import xyz.simoneesposito.ocloud.ui.OCloudUiState
import xyz.simoneesposito.ocloud.ui.OCloudViewModel
import xyz.simoneesposito.ocloud.ui.PhotoViewer
import xyz.simoneesposito.ocloud.ui.theme.OCloudTheme
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debugAuthPreview = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("ocloud.debug_auth_preview")
        } else {
            null
        }
        setContent {
            OCloudTheme {
                when (debugAuthPreview) {
                    "verification-code" -> VerificationCodePreviewScreen()
                    "photos-access" -> AuthorizationScreen(
                        message = null,
                    )
                    else -> {
                        val app = application as OCloudApplication
                        val model: OCloudViewModel = viewModel(factory = OCloudViewModel.Factory(app))
                        OCloudRoot(app, model)
                    }
                }
            }
        }
    }

    fun requestProtectedAlbumAccess(album: PhotoAlbum, model: OCloudViewModel) {
        try {
            val builder = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.private_album_unlock_title))
                .setSubtitle(getString(R.string.private_album_unlock_subtitle))
            builder.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            builder.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    model.openAlbumAfterAuthentication(album)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    model.showNotice(getString(R.string.private_album_locked))
                }
            })
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.private_album_setup_lock, getString(R.string.brand_android)), Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun OCloudRoot(app: OCloudApplication, model: OCloudViewModel) {
    val state by model.state.collectAsState()
    val auth = state.authState
    when {
        state.isRestoringSession && auth == AuthState.LoggedOut -> StartupScreen()
        auth is AuthState.Authenticated -> LibraryScreen(model, state)
        auth == AuthState.EstablishingPCS || auth == AuthState.RequiresTrustedDeviceApproval -> AuthorizationScreen(state.authMessage)
        auth == AuthState.RequiresTwoFactor || auth == AuthState.VerifyingTwoFactor -> TwoFactorScreen(app, model, state)
        auth is AuthState.Failed -> when (auth.reason) {
            AuthFailure.PcsUnavailable -> AuthorizationScreen(state.authMessage, retry = model::retryPhotosApproval)
            AuthFailure.InvalidVerificationCode -> TwoFactorScreen(app, model, state)
            else -> if (state.hasCompletedOnboarding) SignInScreen(model, state) else OnboardingScreen(model::completeOnboarding)
        }
        !state.hasCompletedOnboarding -> OnboardingScreen(model::completeOnboarding)
        else -> SignInScreen(model, state)
    }
}

@Composable
private fun StartupScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.brand_iphotos), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.startup_checking_session, stringResource(R.string.brand_apple)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier.fillMaxSize()
                .padding(insets)
                .padding(horizontal = 24.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 28.dp),
            ) {
                Box(
                    Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.onboarding_welcome, stringResource(R.string.brand_iphotos)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.onboarding_title, stringResource(R.string.brand_iphotos), stringResource(R.string.brand_android)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.onboarding_subtitle, stringResource(R.string.live_photos_name)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
                OnboardingBenefit(
                    Icons.Outlined.PhotoLibrary,
                    stringResource(R.string.onboarding_photos_title, stringResource(R.string.brand_iphotos)),
                    stringResource(R.string.onboarding_photos_body, stringResource(R.string.brand_iphotos), stringResource(R.string.brand_apple)),
                )
                Spacer(Modifier.height(18.dp))
                OnboardingBenefit(
                    Icons.Outlined.Security,
                    stringResource(R.string.onboarding_password_title),
                    stringResource(R.string.onboarding_password_body, stringResource(R.string.brand_apple)),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.continue_label))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnboardingBenefit(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(21.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SignInScreen(model: OCloudViewModel, state: OCloudUiState) {
    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = state.authState == AuthState.Authenticating
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.signin_title, stringResource(R.string.brand_iaccount)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.signin_subtitle, stringResource(R.string.brand_iphotos)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = appleId,
                onValueChange = { appleId = it },
                label = { Text(stringResource(R.string.brand_iaccount)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.authMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.authMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val oneTimePassword = password
                    password = ""
                    model.signIn(appleId, oneTimePassword)
                },
                enabled = !busy && appleId.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Text(stringResource(R.string.connecting_to_apple, stringResource(R.string.brand_apple)))
                    }
                } else Text(stringResource(R.string.sign_in))
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.apple_signin_wait, stringResource(R.string.brand_apple)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.password_privacy, stringResource(R.string.brand_apple), stringResource(R.string.brand_iphotos)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TwoFactorScreen(app: OCloudApplication, model: OCloudViewModel, state: OCloudUiState) {
    var code by remember { mutableStateOf("") }
    var smsPhone by remember { mutableStateOf<TrustedPhone?>(null) }
    val phones by app.authManager.trustedPhones.collectAsState()
    val busy = state.authState == AuthState.VerifyingTwoFactor || state.authState == AuthState.EstablishingPCS
    CenteredAuthFrame(
        icon = Icons.Outlined.Lock,
        title = stringResource(R.string.verify_its_you),
        subtitle = stringResource(R.string.verification_code_subtitle, stringResource(R.string.brand_apple)),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(8) },
            label = { Text(stringResource(R.string.verification_code)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.authMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(state.authMessage, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { model.verifyCode(code, smsPhone) },
            enabled = !busy && code.length >= 4,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Text(if (state.authState == AuthState.VerifyingTwoFactor) stringResource(R.string.checking_code) else stringResource(R.string.connecting_to_photos))
                }
            } else Text(stringResource(R.string.verify_code))
        }
        if (phones.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.get_code_by_sms), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            phones.forEach { phone ->
                AssistChip(
                    onClick = {
                        smsPhone = phone
                        model.requestSms(phone)
                    },
                    label = { Text(phone.display.ifBlank { stringResource(R.string.trusted_phone) }) },
                    leadingIcon = { Icon(Icons.Outlined.Sms, null) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = model::logout, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.cancel_sign_in)) }
    }
}

@Composable
private fun AuthorizationScreen(message: String?, retry: (() -> Unit)? = null) {
    CenteredAuthFrame(
        icon = Icons.Outlined.CloudDone,
        title = stringResource(if (retry == null) R.string.allow_photos_access else R.string.photos_access_attention),
        subtitle = stringResource(R.string.approval_subtitle, stringResource(R.string.brand_idevice), stringResource(R.string.brand_iphotos)),
    ) {
        if (retry == null) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        if (retry == null) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.waiting_for_confirmation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (retry != null && message != null) {
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        if (retry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry_photos_access)) }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.recovery_key_safety, stringResource(R.string.brand_iphotos)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VerificationCodePreviewScreen() {
    var code by remember { mutableStateOf("") }
    CenteredAuthFrame(
        icon = Icons.Outlined.Lock,
        title = stringResource(R.string.verify_its_you),
        subtitle = stringResource(R.string.verification_code_subtitle, stringResource(R.string.brand_apple)),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(8) },
            label = { Text(stringResource(R.string.verification_code)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text(stringResource(R.string.verify_code))
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = {}, enabled = false, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.cancel_sign_in))
        }
    }
}

@Composable
private fun CenteredAuthFrame(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(26.dp))
            content()
        }
    }
}

@Composable
private fun LibraryScreen(model: OCloudViewModel, state: OCloudUiState) {
    val activity = LocalContext.current as? MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    SideEffect {
        if (state.selectedAlbum?.requiresDeviceAuthentication() == true) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(
        enabled = state.selectedPhoto != null || state.selectedAlbum != null || state.tab == AppTab.Downloads,
    ) {
        when {
            state.selectedPhoto != null -> model.photoSelected(null)
            state.selectedAlbum != null -> model.backFromAlbum()
            else -> model.selectTab(AppTab.Settings)
        }
    }
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> model.lockProtectedAlbum()
                Lifecycle.Event.ON_START -> model.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf(AppTab.Photos, AppTab.Collections, AppTab.Settings).forEach { tab ->
                    NavigationBarItem(
                        selected = (state.tab == tab && state.selectedAlbum == null) ||
                            (tab == AppTab.Settings && state.tab == AppTab.Downloads),
                        onClick = if (tab == AppTab.Photos) model::showAllPhotos else ({ model.selectTab(tab) }),
                        icon = { Icon(tabIcon(tab), contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes), maxLines = 1) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            LibraryHeader(state, model)
            if (state.notice != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = model::dismissNotice) { Text(stringResource(R.string.dismiss)) }
                }
            }
            when (state.tab) {
                AppTab.Photos -> GalleryContent(state, model)
                AppTab.Collections -> CollectionsContent(state, model)
                AppTab.Downloads -> DownloadsContent(state, model)
                AppTab.Settings -> SettingsContent(state, model)
            }
        }
    }
    state.selectedPhoto?.let { asset -> PhotoViewer(asset, model) { model.photoSelected(null) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHeader(state: OCloudUiState, model: OCloudViewModel) {
    val title = when {
        state.selectedAlbum != null -> albumDisplayTitle(state.selectedAlbum.title)
        else -> stringResource(state.tab.labelRes)
    }
    TopAppBar(
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (state.tab == AppTab.Photos && state.selectedAlbum == null) {
                    val totalItems = state.albums.firstOrNull { it.title == "All Photos" }?.assetCount
                    val subtitle = totalItems?.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.item_count, it, NumberFormat.getIntegerInstance().format(it))
                    } ?: if (state.hasMore) stringResource(R.string.your_iphotos_library, stringResource(R.string.brand_iphotos))
                    else pluralStringResource(R.plurals.item_count, state.photos.size, NumberFormat.getIntegerInstance().format(state.photos.size))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            if (state.selectedAlbum != null) IconButton(onClick = model::backFromAlbum) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back_to_collections))
            } else if (state.tab == AppTab.Downloads) IconButton(onClick = { model.selectTab(AppTab.Settings) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back_to_more))
            }
        },
    )
}

private fun tabIcon(tab: AppTab) = when (tab) {
    AppTab.Photos -> Icons.Outlined.PhotoLibrary
    AppTab.Collections -> Icons.Outlined.Album
    AppTab.Downloads -> Icons.Outlined.DownloadForOffline
    AppTab.Settings -> Icons.Outlined.MoreHoriz
}

@Composable
private fun albumDisplayTitle(title: String): String = when {
    title.equals("All Photos", ignoreCase = true) -> stringResource(R.string.all_photos)
    title.equals("Favorites", ignoreCase = true) -> stringResource(R.string.favorites)
    title.equals("Recently Deleted", ignoreCase = true) -> stringResource(R.string.recently_deleted)
    title.equals("Hidden", ignoreCase = true) -> stringResource(R.string.hidden)
    else -> title
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryContent(
    state: OCloudUiState,
    model: OCloudViewModel,
    emptyTitle: String? = null,
    emptyBody: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (state.photos.isEmpty() && (state.loading || state.loadingMore)) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.photos.isEmpty()) {
        if (state.pageError != null) {
            Column(
                modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.photos_could_not_load), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(state.pageError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = model::retryPhotoPage) { Text(stringResource(R.string.try_again)) }
            }
            return
        }
        EmptyState(
            icon = Icons.Outlined.PhotoLibrary,
            title = emptyTitle ?: stringResource(R.string.library_empty_title),
            body = emptyBody ?: stringResource(R.string.library_empty_body, stringResource(R.string.brand_iphotos)),
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    val unknownDate = stringResource(R.string.unknown_date)
    val entries = remember(state.photos, unknownDate) { galleryEntries(state.photos, unknownDate) }
    val gridState = rememberLazyGridState()
    var densityStep by remember { mutableIntStateOf(1) }
    LoadNextPageWhenNearEnd(gridState, entries.size, state.hasMore, state.loadingMore, state.pageError, model)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(listOf(140.dp, 108.dp, 78.dp)[densityStep]),
        state = gridState,
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.fillMaxSize()
            .semantics { contentDescription = context.getString(R.string.photo_grid_description) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var initialDistance = 0f
                    var changedDensity = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val first = pressed[0].position
                            val second = pressed[1].position
                            val distance = hypot(second.x - first.x, second.y - first.y)
                            if (initialDistance == 0f) initialDistance = distance
                            else if (!changedDensity && initialDistance > 0f) {
                                val ratio = distance / initialDistance
                                if (ratio < 0.82f) {
                                    densityStep = (densityStep + 1).coerceAtMost(2)
                                    changedDensity = true
                                } else if (ratio > 1.22f) {
                                    densityStep = (densityStep - 1).coerceAtLeast(0)
                                    changedDensity = true
                                }
                            }
                            pressed.forEach { it.consume() }
                        } else {
                            initialDistance = 0f
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        items(
            items = entries,
            key = { entry -> when (entry) { is GalleryEntry.Header -> "header-${entry.title}"; is GalleryEntry.Asset -> entry.photo.id } },
            span = { entry -> GridItemSpan(if (entry is GalleryEntry.Header) maxLineSpan else 1) },
        ) { entry ->
            when (entry) {
                is GalleryEntry.Header -> Text(
                    entry.title,
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 18.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                is GalleryEntry.Asset -> PhotoTile(entry.photo, model)
            }
        }
        if (state.pageError != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.pageError, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = model::retryPhotoPage) { Text(stringResource(R.string.try_again)) }
            }
        } else if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.loading_more_photos), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DownloadsContent(state: OCloudUiState, model: OCloudViewModel) {
    val unfinished = state.downloadTasks.filter { it.status != "succeeded" }
    Column(Modifier.fillMaxSize()) {
        if (unfinished.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                unfinished.take(4).forEach { task ->
                    Text(
                        text = when (task.status) {
                            "queued" -> stringResource(R.string.download_waiting)
                            "downloading", "retrying" -> stringResource(R.string.downloading_media)
                            "paused_auth" -> stringResource(R.string.sign_in_to_resume)
                            else -> stringResource(R.string.download_failed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (unfinished.any { it.status in setOf("failed", "paused_auth") }) {
                    TextButton(onClick = model::retryDownloads) { Text(stringResource(R.string.retry_downloads)) }
                }
            }
        }
        GalleryContent(
            state = state,
            model = model,
            emptyTitle = stringResource(R.string.nothing_downloaded),
            emptyBody = stringResource(R.string.downloads_empty_body, stringResource(R.string.brand_iphotos)),
        )
    }
}

@Composable
private fun LoadNextPageWhenNearEnd(state: LazyGridState, itemCount: Int, hasMore: Boolean, loading: Boolean, error: String?, model: OCloudViewModel) {
    LaunchedEffect(state, itemCount, hasMore, loading, error) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { last -> if (hasMore && !loading && error == null && last >= itemCount - 8) model.loadMore() }
    }
}

private sealed interface GalleryEntry {
    data class Header(val title: String) : GalleryEntry
    data class Asset(val photo: PhotoAsset) : GalleryEntry
}

private fun galleryEntries(photos: List<PhotoAsset>, unknownDate: String): List<GalleryEntry> {
    val result = ArrayList<GalleryEntry>(photos.size + 12)
    var lastMonth: String? = null
    photos.forEach { photo ->
        val month = runCatching {
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
                .format(Instant.ofEpochMilli(photo.capturedAtMillis).atZone(ZoneId.systemDefault()))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }.getOrDefault(unknownDate)
        if (month != lastMonth) {
            result += GalleryEntry.Header(month)
            lastMonth = month
        }
        result += GalleryEntry.Asset(photo)
    }
    return result
}

@Composable
private fun PhotoTile(asset: PhotoAsset, model: OCloudViewModel) {
    val context = LocalContext.current
    var bytes by remember(asset.id) { mutableStateOf<ByteArray?>(null) }
    var retryToken by remember(asset.id) { mutableIntStateOf(0) }
    var loading by remember(asset.id) { mutableStateOf(true) }
    var failed by remember(asset.id) { mutableStateOf(false) }
    LaunchedEffect(asset.id, retryToken) {
        loading = true
        failed = false
        suspend fun request(): ByteArray? = try {
            model.thumbnail(asset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        var result = request()
        if (result == null) {
            delay(900.milliseconds)
            result = request()
        }
        bytes = result
        failed = result == null
        loading = false
    }
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { model.photoSelected(asset) }
            .semantics { contentDescription = context.getString(R.string.photo_tile_description, mediaKindLabel(asset.mediaKind, context), asset.title) },
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else if (loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        } else {
            Icon(
                if (asset.mediaKind == MediaKind.Video) Icons.Outlined.Movie else Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (failed) {
            IconButton(
                onClick = { retryToken++ },
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(40.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.retry_loading_photo, asset.title), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (asset.isFavorite && bitmap != null) Icon(Icons.Outlined.Favorite, contentDescription = stringResource(R.string.favorite), tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(17.dp))
        if (asset.mediaKind == MediaKind.Video) {
            Icon(Icons.Outlined.Movie, contentDescription = stringResource(R.string.video), tint = if (bitmap != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(18.dp))
        }
    }
}

@Composable
private fun CollectionsContent(state: OCloudUiState, model: OCloudViewModel) {
    val activity = LocalContext.current as? MainActivity
    val privateAuthUnavailable = stringResource(R.string.private_album_auth_unavailable)
    when {
        state.loading && state.albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.albums.isEmpty() -> EmptyState(Icons.Outlined.Album, stringResource(R.string.no_collections), stringResource(R.string.collections_empty_body, stringResource(R.string.brand_iphotos)), Modifier.fillMaxSize())
        else -> {
            val favorites = state.albums.firstOrNull { it.title.equals("Favorites", ignoreCase = true) }
            val recentlyDeleted = state.albums.firstOrNull { it.title.equals("Recently Deleted", ignoreCase = true) }
            val hidden = state.albums.firstOrNull { it.title.equals("Hidden", ignoreCase = true) }
            val quickAccess = listOfNotNull(favorites, hidden, recentlyDeleted)
            val specialIds = quickAccess.mapTo(mutableSetOf(), PhotoAlbum::id)
            val albums = state.albums.filterNot { album ->
                album.id in specialIds || album.title.equals("All Photos", ignoreCase = true)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (quickAccess.isNotEmpty()) {
                    Text(
                        stringResource(R.string.quick_access),
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        quickAccess.forEach { album ->
                            SpecialCollectionTile(
                                album = album,
                                cover = state.albumCoverAssets[album.id],
                                model = model,
                                modifier = Modifier.weight(1f),
                                onClick = { openCollection(album, activity, model, privateAuthUnavailable) },
                            )
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    Text(
                        stringResource(R.string.albums),
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    albums.chunked(2).forEach { rowAlbums ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowAlbums.forEach { album ->
                                AlbumCollectionTile(
                                    album = album,
                                    cover = state.albumCoverAssets[album.id],
                                    model = model,
                                    modifier = Modifier.weight(1f),
                                    onClick = { openCollection(album, activity, model, privateAuthUnavailable) },
                                )
                            }
                            if (rowAlbums.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SpecialCollectionTile(
    album: PhotoAlbum,
    cover: PhotoAsset?,
    model: OCloudViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    LaunchedEffect(album.id) { if (!album.requiresDeviceAuthentication()) model.ensureAlbumCover(album) }
    val protected = album.requiresDeviceAuthentication()
    val favorite = album.title.equals("Favorites", ignoreCase = true)
    val recentlyDeleted = album.title.equals("Recently Deleted", ignoreCase = true)
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.92f).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (favorite) AlbumCoverImage(album, cover, model)
            else Icon(
                when {
                    recentlyDeleted -> Icons.Outlined.DeleteOutline
                    protected -> Icons.Outlined.Lock
                    else -> Icons.Outlined.Album
                },
                contentDescription = when {
                    recentlyDeleted -> stringResource(R.string.recently_deleted)
                    protected -> stringResource(R.string.locked_private_collection)
                    else -> null
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            if (favorite) {
                Icon(Icons.Outlined.Favorite, stringResource(R.string.favorites), tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(19.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(albumDisplayTitle(album.title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!protected) Text(pluralStringResource(R.plurals.item_count, album.assetCount, NumberFormat.getIntegerInstance().format(album.assetCount)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun AlbumCollectionTile(
    album: PhotoAlbum,
    cover: PhotoAsset?,
    model: OCloudViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    LaunchedEffect(album.id) { if (!album.requiresDeviceAuthentication()) model.ensureAlbumCover(album) }
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { AlbumCoverImage(album, cover, model) }
        Spacer(Modifier.height(8.dp))
        Text(albumDisplayTitle(album.title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!album.requiresDeviceAuthentication()) {
            Text(pluralStringResource(R.plurals.item_count, album.assetCount, NumberFormat.getIntegerInstance().format(album.assetCount)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openCollection(album: PhotoAlbum, activity: MainActivity?, model: OCloudViewModel, unavailableMessage: String) {
    if (album.requiresDeviceAuthentication()) {
        if (activity != null) activity.requestProtectedAlbumAccess(album, model)
        else model.showNotice(unavailableMessage)
    } else model.openAlbum(album)
}

@Composable
private fun AlbumCoverImage(album: PhotoAlbum, cover: PhotoAsset?, model: OCloudViewModel) {
    val protected = album.requiresDeviceAuthentication()
    var bytes by remember(album.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(album.id, cover?.id) {
        bytes = if (protected || cover == null) null else try {
            model.thumbnail(cover)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (protected) {
        Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.private_album_locked_description), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
    } else if (bitmap != null) {
        Image(bitmap, contentDescription = stringResource(R.string.latest_photo_in_album, albumDisplayTitle(album.title)), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Icon(Icons.Outlined.Album, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun SettingsContent(state: OCloudUiState, model: OCloudViewModel) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsGroup(stringResource(R.string.settings_library)) {
            SettingSwitch(
                Icons.Outlined.Refresh,
                stringResource(R.string.automatic_sync),
                stringResource(R.string.automatic_sync_detail),
                state.autoSyncEnabled,
                model::setAutoSyncEnabled,
            )
            HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingLink(Icons.Outlined.DownloadForOffline, stringResource(R.string.tab_downloads), pluralStringResource(R.plurals.media_item_count, state.downloadTasks.size, state.downloadTasks.size)) {
                model.selectTab(AppTab.Downloads)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Text(
                stringResource(
                    R.string.privacy_summary,
                    stringResource(R.string.brand_iphotos),
                    stringResource(R.string.brand_apple),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = model::logout,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(title)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            content = { Column(content = content) },
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun mediaKindLabel(kind: MediaKind, context: android.content.Context): String = when (kind) {
    MediaKind.Photo -> context.getString(R.string.media_photo)
    MediaKind.Video -> context.getString(R.string.media_video)
    MediaKind.Raw -> context.getString(R.string.media_raw_photo)
    MediaKind.LivePhoto -> context.getString(R.string.media_live_photo, context.getString(R.string.live_photos_name))
}

@Composable
private fun SettingLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun SettingSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
