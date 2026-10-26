package com.timeledger.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 色板 —— 「清晨蓝」（模块 27 UI 鲜活化）。
 *
 * ## 一条原则：分类色只做点睛，不做填充
 * 这个 App 的颜色是有**语义**的：分类色来自数据库（`Category.colorArgb`）。
 * 所以中性色必须足够安静，把饱和度留给分类色；否则七个分类同屏时整页都在吵。
 * 具体做法写在 `AppCard` / `TimelineBand` 里：分类色以 **12~16% 透明度的底 +
 * 满饱和度的一条色带**出现，而不是整块刷满。
 *
 * ## 为什么不用 Material You 动态取色
 * 从模块 6 起就定过：整体色板若随壁纸变化，分类色可能和主题色撞在一起，
 * 反而更难分辨。这条到鲜活化这一轮依然成立。
 *
 * ## 页面底 / 卡片底 / 浮起底 三层
 * 浅色下页面底是**纸感的暖白**（`#FAF7F3`），卡片是更亮的暖白 —— 层次靠明度差，
 * 不靠描边，所以卡片可以没有边框（这是「不像表单工具」的关键一步）。
 */
private val MorningBlue = Color(0xFF3F6BFF)
private val MorningBlueSoft = Color(0xFFE4EAFF)

/**
 * 浅色：页面底 / 卡片 / 浮起 / 内嵌。
 *
 * **中性色一律带暖调**（第一版是冷灰 `#F5F6FA`，装到手机上第一句反馈就是「偏冷」）。
 * 现在页面底是纸感的暖白、文字是暖近黑、描边是暖灰 —— 蓝只作为**点缀**出现，
 * 整页读起来是「清晨的纸」而不是「控制台」。
 * 分类色里本来就以暖色居多（橙/红/黄），暖底也让它们更服帖。
 */
private val LightBackground = Color(0xFFFAF7F3)
private val LightSurface = Color(0xFFFFFDFB)
private val LightSurfaceContainer = Color(0xFFF3EEE7)
private val LightSurfaceHigh = Color(0xFFEDE7DE)

private val LightOnSurface = Color(0xFF1F1B16)
private val LightOnSurfaceVariant = Color(0xFF6B6154)
private val LightOutline = Color(0xFFD8CEC0)
private val LightOutlineVariant = Color(0xFFEBE4D9)

/** 深色：同样走暖 —— 深底色下暖近黑比冷灰更「有体温」，也不会和暖色分类撞。 */
private val DarkBackground = Color(0xFF15120E)
private val DarkSurface = Color(0xFF1C1815)
private val DarkSurfaceContainer = Color(0xFF262119)
private val DarkSurfaceHigh = Color(0xFF312A21)

/** 语义色：达成用绿、警示用琥珀、删除用红 —— 三者都压低饱和度，只用在中性底上。 */
val SuccessLight = Color(0xFF2E7D5B)
val SuccessDark = Color(0xFF6FCF97)
val WarningLight = Color(0xFF9A6700)
val WarningDark = Color(0xFFE0B252)

/**
 * 分类色的兜底。
 *
 * 数据库里没配色的分类（`colorArgb = null`）按顺序取这里 —— 与 `AppCategory`
 * 的柔和色系一致，保证「没配色的分类」也有身份，而不是一坨灰。
 */
val CategoryFallbackColors = listOf(
    Color(0xFF3F6BFF),
    Color(0xFF2E9E7E),
    Color(0xFFC2703D),
    Color(0xFF8E63C9),
    Color(0xFFC2557A),
    Color(0xFF3E8FB0),
    Color(0xFF7A8B3C),
    Color(0xFF9A6700),
)

internal val LightScheme = lightColorScheme(
    primary = MorningBlue,
    onPrimary = Color.White,
    primaryContainer = MorningBlueSoft,
    onPrimaryContainer = Color(0xFF11205C),
    secondary = Color(0xFF6A6154),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE6DC),
    onSecondaryContainer = Color(0xFF241F18),
    tertiary = SuccessLight,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFDFAF7),
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFE6DFD4),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

internal val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8BAFF),
    onPrimary = Color(0xFF0A1A5C),
    primaryContainer = Color(0xFF24356F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFB5A995),
    onSecondary = Color(0xFF241F18),
    secondaryContainer = Color(0xFF332C22),
    onSecondaryContainer = Color(0xFFEDE4D6),
    tertiary = SuccessDark,
    onTertiary = Color(0xFF06291C),
    background = DarkBackground,
    onBackground = Color(0xFFEFE9E2),
    surface = DarkSurface,
    onSurface = Color(0xFFEFE9E2),
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = Color(0xFFB3A794),
    surfaceContainerLowest = Color(0xFF100E0B),
    surfaceContainerLow = Color(0xFF191512),
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF3B332A),
    outline = Color(0xFF4E463B),
    outlineVariant = Color(0xFF332C24),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0A08),
    errorContainer = Color(0xFF5C1B16),
    onErrorContainer = Color(0xFFFFDAD6),
)
