package com.iamcanincan.opticon.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 应用主题：Material 3 Expressive。
 *
 * Android 12 及以上走系统的动态取色（壁纸取色）；12 以下退回下面这套以品牌樱粉
 * 为主色的配色 —— 保证任何设备上都不会出现默认紫。
 *
 * 圆角档位取两边的折中：`large = 20dp`，比 Material 3 标准的 12dp 软、比
 * Expressive 的 32dp 克制。界面里所有卡片都显式用 `MaterialTheme.shapes.large`，
 * 改这一个数就能整体调圆角。
 */

/** 品牌樱粉（与桌面图标底板同一色系） */
private val BrandPink = Color(0xFFF9A8C4)
private val BrandPinkDeep = Color(0xFF9C4067)
private val BrandInk = Color(0xFF031019)

private val OpticonShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightColors = lightColorScheme(
    primary = BrandPinkDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD8E6),
    onPrimaryContainer = Color(0xFF3E0021),
    secondary = Color(0xFF74565F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD8E6),
    onSecondaryContainer = Color(0xFF2B151C),
    tertiary = Color(0xFF7C5635),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFFFF8F8),
    onBackground = BrandInk,
    surface = Color(0xFFFFF8F8),
    onSurface = BrandInk,
    surfaceVariant = Color(0xFFF3DDE3),
    onSurfaceVariant = Color(0xFF524348),
    outline = Color(0xFF847377),
    outlineVariant = Color(0xFFD6C2C6)
)

private val DarkColors = darkColorScheme(
    primary = BrandPink,
    onPrimary = Color(0xFF5E1136),
    primaryContainer = Color(0xFF7C294D),
    onPrimaryContainer = Color(0xFFFFD8E6),
    secondary = Color(0xFFE3BDC7),
    onSecondary = Color(0xFF422931),
    secondaryContainer = Color(0xFF5A3F47),
    onSecondaryContainer = Color(0xFFFFD8E6),
    tertiary = Color(0xFFEFBD94),
    onTertiary = Color(0xFF472A0E),
    tertiaryContainer = Color(0xFF613F21),
    onTertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF1A1114),
    onBackground = Color(0xFFF0DEE2),
    surface = Color(0xFF1A1114),
    onSurface = Color(0xFFF0DEE2),
    surfaceVariant = Color(0xFF524348),
    onSurfaceVariant = Color(0xFFD6C2C6),
    outline = Color(0xFF9E8C90),
    outlineVariant = Color(0xFF524348)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpticonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    // MaterialExpressiveTheme 默认带 Expressive 的弹簧动效（MotionScheme.expressive）
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        shapes = OpticonShapes,
        content = content
    )
}
