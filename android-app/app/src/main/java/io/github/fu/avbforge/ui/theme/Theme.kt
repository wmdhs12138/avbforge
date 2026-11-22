package io.github.fu.avbforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Crafted M3 Expressive Ocean-Blue & Emerald-Teal Dark Scheme (AMOLED-friendly)
private val OceanDarkScheme = darkColorScheme(
    primary = Color(0xFF7EB3FF),
    onPrimary = Color(0xFF002D64),
    primaryContainer = Color(0xFF15386B),
    onPrimaryContainer = Color(0xFFD7E6FF),
    secondary = Color(0xFF5EDBBD),
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF0E3F33),
    onSecondaryContainer = Color(0xFFB7F6E4),
    tertiary = Color(0xFFCBB2FF),
    onTertiary = Color(0xFF331B62),
    tertiaryContainer = Color(0xFF432976),
    onTertiaryContainer = Color(0xFFEADDFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1518),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6ECF5),
    onSurfaceVariant = Color(0xFF98A4B8),
    outline = Color(0xFF404A5A),
    outlineVariant = Color(0xFF252D3A),
    surfaceContainerLowest = Color(0xFF151B24),
    surfaceContainerLow = Color(0xFF18202B),
    surfaceContainer = Color(0xFF1E2734),
    surfaceContainerHigh = Color(0xFF253040),
    surfaceContainerHighest = Color(0xFF2D394C),
)

// Crafted M3 Expressive Light Scheme (Crisp white grouped cards on cool #F3F6FB canvas)
private val OceanLightScheme = lightColorScheme(
    primary = Color(0xFF1A5FD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF072B66),
    secondary = Color(0xFF0B7D60),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFF4E7),
    onSecondaryContainer = Color(0xFF003829),
    tertiary = Color(0xFF6B4BB0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECE0FF),
    onTertiaryContainer = Color(0xFF280D5E),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE1DF),
    onErrorContainer = Color(0xFF5A0B0E),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF141B26),
    surface = Color(0xFFF3F6FB),
    onSurface = Color(0xFF141B26),
    onSurfaceVariant = Color(0xFF58657A),
    outline = Color(0xFFB5C0D2),
    outlineVariant = Color(0xFFDEE5F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFECF0F8),
    surfaceContainer = Color(0xFFE5ECF6),
    surfaceContainerHigh = Color(0xFFDCE4F0),
    surfaceContainerHighest = Color(0xFFD3DCEB),
)

val ExpressiveTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AVBForgeTheme(
    useDynamicColor: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) OceanDarkScheme else OceanLightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
