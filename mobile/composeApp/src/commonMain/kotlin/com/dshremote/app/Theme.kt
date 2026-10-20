package com.dshremote.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DSH token mirror. The Web client uses a white base, a #F9FAFB sidebar, a
 * near-black brand/primary button, and DeepSeek blue only for links and
 * business state. These values are the light/dark aliases from
 * packages/client/ui-theme/src/styles/design-platform.css.
 */
object DshTokens {
    val baseLight = Color(0xFFFFFFFF)
    val sidebarLight = Color(0xFFF9FAFB)
    val layerLight = Color(0xFFFFFFFF)
    val labelLight = Color(0xFF0F1115)
    val secondaryLight = Color(0xFF61666B)
    val tertiaryLight = Color(0xFF81858C)
    val captionLight = Color(0xFFADB2B8)
    val bubbleLight = Color(0xFFEDF3FE)
    val bubbleStrongLight = Color(0xFFD3E2FF)
    val codeLight = Color(0xFFF9FAFB)
    val hoverLight = Color(0xFFF1F3F5)
    val activeLight = Color(0xFFEBEEF2)
    val linkLight = Color(0xFF315FC4)
    val errorLight = Color(0xFFEC1313)
    val successLight = Color(0xFF22C55E)
    val warnLight = Color(0xFFF59E0B)
    val borderLight = Color(0x1A000000)
    val borderStrongLight = Color(0x29000000)

    val baseDark = Color(0xFF151517)
    val sidebarDark = Color(0xFF1B1B1C)
    val layerDark = Color(0xFF1B1B1C)
    val labelDark = Color(0xFFF9FAFB)
    val secondaryDark = Color(0xFFCFD3D6)
    val tertiaryDark = Color(0xFFADB2B8)
    val captionDark = Color(0xFF81858C)
    val bubbleDark = Color(0xFF2C2C2E)
    val bubbleStrongDark = Color(0xFF43454A)
    val codeDark = Color(0xFF1B1B1C)
    val hoverDark = Color(0xFF2C2C2E)
    val activeDark = Color(0xFF353436)
    val linkDark = Color(0xFF7AAAFF)
    val errorDark = Color(0xFFF25A5A)
    val successDark = Color(0xFF22C55E)
    val warnDark = Color(0xFFF59E0B)
    val borderDark = Color(0x1FFFFFFF)
    val borderStrongDark = Color(0x29FFFFFF)
}

private val DshTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val DshShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
)

private val LightColors = lightColorScheme(
    background = DshTokens.baseLight,
    onBackground = DshTokens.labelLight,
    primary = DshTokens.labelLight,
    onPrimary = Color.White,
    primaryContainer = DshTokens.bubbleLight,
    onPrimaryContainer = DshTokens.labelLight,
    secondary = DshTokens.linkLight,
    onSecondary = Color.White,
    secondaryContainer = DshTokens.bubbleLight,
    onSecondaryContainer = DshTokens.labelLight,
    tertiary = DshTokens.secondaryLight,
    surface = DshTokens.baseLight,
    onSurface = DshTokens.labelLight,
    surfaceVariant = DshTokens.sidebarLight,
    onSurfaceVariant = DshTokens.secondaryLight,
    surfaceContainer = DshTokens.sidebarLight,
    surfaceContainerHigh = DshTokens.sidebarLight,
    surfaceContainerHighest = DshTokens.hoverLight,
    outline = DshTokens.borderStrongLight,
    outlineVariant = DshTokens.borderLight,
    error = DshTokens.errorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFF57120C),
)

private val DarkColors = darkColorScheme(
    background = DshTokens.baseDark,
    onBackground = DshTokens.labelDark,
    primary = DshTokens.labelDark,
    onPrimary = Color(0xFF0F1115),
    primaryContainer = DshTokens.bubbleDark,
    onPrimaryContainer = DshTokens.labelDark,
    secondary = DshTokens.linkDark,
    onSecondary = Color(0xFF062E6B),
    secondaryContainer = DshTokens.bubbleDark,
    onSecondaryContainer = DshTokens.labelDark,
    tertiary = DshTokens.secondaryDark,
    surface = DshTokens.baseDark,
    onSurface = DshTokens.labelDark,
    surfaceVariant = DshTokens.sidebarDark,
    onSurfaceVariant = DshTokens.secondaryDark,
    surfaceContainer = DshTokens.sidebarDark,
    surfaceContainerHigh = DshTokens.sidebarDark,
    surfaceContainerHighest = DshTokens.hoverDark,
    outline = DshTokens.borderStrongDark,
    outlineVariant = DshTokens.borderDark,
    error = DshTokens.errorDark,
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF57120C),
    onErrorContainer = Color(0xFFFEE2E2),
)

private val LocalDshDark = staticCompositionLocalOf { false }

@Composable
fun DSHRemoteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDshDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = DshTypography,
            shapes = DshShapes,
            content = content,
        )
    }
}

/** Semantic surface helpers that Material's color roles do not name. */
object DshSurfaces {
    val success: Color
        @Composable get() = if (LocalDshDark.current) Color(0xFF6FD79B) else Color(0xFF16743B)
    val sidebar: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.sidebarDark else DshTokens.sidebarLight
    val base: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.baseDark else DshTokens.baseLight
    val bubble: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.bubbleDark else DshTokens.bubbleLight
    val bubbleStrong: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.bubbleStrongDark else DshTokens.bubbleStrongLight
    val code: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.codeDark else DshTokens.codeLight
    val hover: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.hoverDark else DshTokens.hoverLight
    val active: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.activeDark else DshTokens.activeLight
    val link: Color
        @Composable get() = if (LocalDshDark.current) DshTokens.linkDark else DshTokens.linkLight
}
