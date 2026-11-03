package com.dsh.aihub.m3

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 主题 —— 替换原 Miuix 主题（ui/AppTheme.kt）。
 *
 * 配色策略与原实现一致：**静态配色，不接 Monet 动态取色**。
 * 理由也一致：本 App 是本地网关工具，界面只在用户主动打开时看几眼，
 * 不需要跟随壁纸变色；静态配色的行为完全确定，浅深两套已覆盖需求。
 *
 * 色值取自 Material 3 基线配色的紫调（baseline purple），
 * 与 M3 官方组件的默认外观一致，替换 Miuix 后视觉上不会显得突兀。
 */

// ---------------------------------------------------------------- 浅色

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),

    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),

    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),

    // surface 与 background 刻意取不同值：本应用大量使用 elevated card，
    // 需要卡片与页面底色之间有可辨识的层次，同值会让卡片边界完全消失。
    //
    // 【2026-11-03 调低 surface】原值与 background 完全相同（都是 FEF7FF），
    // 只靠 1dp elevation 的阴影区分卡片 —— 在深色壁纸/低对比屏上几乎看不出边界，
    // 整页读起来就是"一片浅色上飘着一堆文字"，这也是"简陋"观感的一部分。
    // 现在 background 保持极浅的紫底、surface 略亮一点，形成"页面 < 卡片"的正向层次；
    // surfaceContainerHighest 再深一档，专门给仪表盘的指标格用（格子要比卡片更"沉"）。
    surface = Color(0xFFFDF8FD),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    // 容器层级：仪表盘的指标格 / 进度条底槽用 highest 一档。
    // 这四个角色是 M3 1.2+ 的官方 surface container 分级，
    // 不自己配色 —— 主题切换（含将来的动态取色）时它们会自动跟着走。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F3FA),
    surfaceContainer = Color(0xFFF4EEF5),
    surfaceContainerHigh = Color(0xFFEEE8F0),
    surfaceContainerHighest = Color(0xFFE9E3EB),

    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0)
)

// ---------------------------------------------------------------- 深色

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),

    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),

    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),

    // 深色下同样建立"页面 < 卡片 < 格子"的三级层次。
    // 深色主题里 elevation 阴影几乎不可见，只能靠底色递进，
    // 所以 surface 比 background 亮、containerHighest 再亮一档。
    surface = Color(0xFF1B1921),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1B1921),
    surfaceContainer = Color(0xFF1F1D24),
    surfaceContainerHigh = Color(0xFF2A282F),
    surfaceContainerHighest = Color(0xFF35323B),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

/**
 * 主题入口 —— 全 App 唯一的挂载点。
 *
 * 只做一件事：按系统深浅色选一套静态配色并交给 MaterialTheme。
 * 不做 typography / shapes 覆盖：M3 默认值本身就是官方规格，
 * 自定义反而容易偏离（这也是原 Miuix 实现未覆盖 typography 的同一判断）。
 */
@Composable
fun AppM3Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
