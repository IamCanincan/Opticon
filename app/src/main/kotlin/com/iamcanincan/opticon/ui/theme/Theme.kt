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
 * ## 取色策略：**跟随系统取色**
 * Android 12（API 31）及以上走 `dynamicLightColorScheme` / `dynamicDarkColorScheme`，
 * 也就是 Material You 的壁纸取色 —— 界面配色跟着系统/壁纸走。
 * 12 以下没有这套机制，退回下面写死的品牌粉配色。
 *
 * ## 为什么固定配色里每个角色都要写出来
 * 那份配色只在 **Android 12 以下**生效，但同样要写全：`lightColorScheme()` 里
 * 没指定的角色会退回 **MD3 默认的紫色系**。之前就漏了 `surfaceContainerHighest`
 * （界面里用了 5 处：图标方块底板、效果示意图的方块），于是那几块在粉色主题里泛紫灰。
 * ⚠ 以后新增用到的角色，**先在这里补齐**，别依赖默认值。
 *
 * ## 圆角档位
 * `large = 20dp`，比 Material 3 标准的 12dp 软、比 Expressive 的 32dp 克制。
 * 界面里所有卡片都显式用 `MaterialTheme.shapes.large`，改这一个数就能整体调圆角。
 * 形状**不跟随取色**，两套配色下都是同一套圆角。
 */

/** 品牌樱粉（与桌面图标同一色系） */
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
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F8),
    onBackground = BrandInk,
    surface = Color(0xFFFFF8F8),
    onSurface = BrandInk,
    surfaceVariant = Color(0xFFF3DDE3),
    onSurfaceVariant = Color(0xFF524348),
    outline = Color(0xFF847377),
    outlineVariant = Color(0xFFD6C2C6),
    // surface 容器层级：浅色下越靠上越深，用来区分"页面底 / 卡片 / 卡片里的块"
    surfaceDim = Color(0xFFE8D6DA),
    surfaceBright = Color(0xFFFFF8F8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1F3),
    surfaceContainer = Color(0xFFFCEBEF),
    surfaceContainerHigh = Color(0xFFF6E5E9),
    surfaceContainerHighest = Color(0xFFF0E0E4),
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
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1114),
    onBackground = Color(0xFFF0DEE2),
    surface = Color(0xFF1A1114),
    onSurface = Color(0xFFF0DEE2),
    surfaceVariant = Color(0xFF524348),
    onSurfaceVariant = Color(0xFFD6C2C6),
    outline = Color(0xFF9E8C90),
    outlineVariant = Color(0xFF524348),
    surfaceDim = Color(0xFF1A1114),
    surfaceBright = Color(0xFF42373A),
    surfaceContainerLowest = Color(0xFF140C0F),
    surfaceContainerLow = Color(0xFF221A1D),
    surfaceContainer = Color(0xFF261E21),
    surfaceContainerHigh = Color(0xFF31282B),
    surfaceContainerHighest = Color(0xFF3C3336),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpticonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Android 12+：跟随系统取色（壁纸取色）
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    // MaterialExpressiveTheme 默认带 Expressive 的弹簧动效（MotionScheme.expressive）
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        shapes = OpticonShapes,
        content = content,
    )
}
