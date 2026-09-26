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

package xyz.simoneesposito.ocloud.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = OCloudDarkPrimary,
    onPrimary = OCloudDarkOnPrimary,
    primaryContainer = OCloudDarkPrimaryContainer,
    onPrimaryContainer = OCloudDarkOnPrimaryContainer,
    secondary = OCloudDarkSecondary,
    onSecondary = OCloudDarkOnSecondary,
    secondaryContainer = OCloudDarkSecondaryContainer,
    onSecondaryContainer = OCloudDarkOnSecondaryContainer,
    tertiary = OCloudDarkTertiary,
    onTertiary = OCloudDarkOnTertiary,
    tertiaryContainer = OCloudDarkTertiaryContainer,
    onTertiaryContainer = OCloudDarkOnTertiaryContainer,
    error = OCloudDarkError,
    onError = OCloudDarkOnError,
    errorContainer = OCloudDarkErrorContainer,
    onErrorContainer = OCloudDarkOnErrorContainer,
    background = OCloudDarkBackground,
    onBackground = OCloudDarkOnBackground,
    surface = OCloudDarkSurface,
    onSurface = OCloudDarkOnSurface,
    surfaceVariant = OCloudDarkSurfaceVariant,
    onSurfaceVariant = OCloudDarkOnSurfaceVariant,
    outline = OCloudDarkOutline,
    outlineVariant = OCloudDarkOutlineVariant,
    inverseSurface = OCloudLightOnSurface,
    inverseOnSurface = OCloudLightSurface,
    inversePrimary = OCloudPrimary,
    surfaceDim = Color(0xFF111319),
    surfaceBright = Color(0xFF373940),
    surfaceContainerLowest = Color(0xFF0B0D13),
    surfaceContainerLow = Color(0xFF191B22),
    surfaceContainer = Color(0xFF1D1F26),
    surfaceContainerHigh = Color(0xFF272A31),
    surfaceContainerHighest = Color(0xFF32343C),
)

private val LightColorScheme = lightColorScheme(
    primary = OCloudPrimary,
    onPrimary = OCloudOnPrimary,
    primaryContainer = OCloudPrimaryContainer,
    onPrimaryContainer = OCloudOnPrimaryContainer,
    secondary = OCloudSecondary,
    onSecondary = OCloudOnSecondary,
    secondaryContainer = OCloudSecondaryContainer,
    onSecondaryContainer = OCloudOnSecondaryContainer,
    tertiary = OCloudTertiary,
    onTertiary = OCloudOnTertiary,
    tertiaryContainer = OCloudTertiaryContainer,
    onTertiaryContainer = OCloudOnTertiaryContainer,
    error = OCloudError,
    onError = OCloudOnError,
    errorContainer = OCloudErrorContainer,
    onErrorContainer = OCloudOnErrorContainer,
    background = OCloudLightBackground,
    onBackground = OCloudLightOnBackground,
    surface = OCloudLightSurface,
    onSurface = OCloudLightOnSurface,
    surfaceVariant = OCloudLightSurfaceVariant,
    onSurfaceVariant = OCloudLightOnSurfaceVariant,
    outline = OCloudLightOutline,
    outlineVariant = OCloudLightOutlineVariant,
    inverseSurface = OCloudLightOnSurface,
    inverseOnSurface = OCloudLightSurface,
    inversePrimary = OCloudDarkPrimary,
    surfaceDim = Color(0xFFDAD9E1),
    surfaceBright = Color(0xFFFAF9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E1E9),
)

private val OCloudShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OCloudTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = OCloudShapes,
        content = content
    )
}
