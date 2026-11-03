package com.dsh.aihub.m3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Material 3 基础组件层 —— 替换 Miuix 的等价物。
 *
 * 为什么要单独一层而不是直接在各页面写 M3 组件：
 *   原项目 19 个页面、约 4671 行 UI 代码全部依赖 Miuix 的 29 个符号
 *   （Scaffold / Card / NavigationBar / SmallTitle / TextButton / Switch ...）。
 *   逐页改写会把「组件映射」这件事重复 19 遍，任何一处对不齐都会产生视觉漂移。
 *   集中一层后，页面只依赖这里的语义化封装，替换是机械的、可增量验证的。
 *
 * 尺寸依据：本层所有数值取自 Material 3 官方规格
 *   - 间距基数 8dp（M3 的 space100，不是 4dp）
 *   - 卡片圆角 12dp、内边距 16dp
 *   - 按钮视觉高度 40dp、水平内边距 24dp、全圆角（pill）
 *   - 触控目标最小 48x48dp
 *   - 列表项左/右内边距 16dp；图标与文字间距 12dp
 */

// ---------------------------------------------------------------- 间距

/** M3 间距基数 8dp。 */
val SpaceS = 8.dp

/** 卡片内元素之间的间距，M3 规定为 12dp。 */
val SpaceM = 12.dp

/** 卡片内边距与页面边距，M3 规定为 16dp。 */
val SpaceL = 16.dp

/** 卡片间距（页面级纵向堆叠），M3 规定为 8dp。 */
val CardGap = 8.dp

/** 触控目标最小值，M3 规定 ≥48x48dp。 */
val TouchMin = 48.dp

/** 卡片圆角，M3 规定 12dp。 */
val RadiusCard = 12.dp

// ---------------------------------------------------------------- 文本样式

/**
 * 页面级小标题（原 Miuix 的 SmallTitle）。
 *
 * 对应 M3 的 Title Small（14sp / 500 / line-height 20sp）——
 * 比 Body Medium 略重但不大，用于分组标题而非页面主标题。
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        modifier = modifier.padding(
            start = SpaceL,
            end = SpaceL,
            top = SpaceM,
            bottom = SpaceS
        ),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = color
    )
}

/**
 * 正文标签（替代 Miuix 的 TextStyle.main）。
 *
 * 用 Body Large（16sp / 400）而不是 Body Medium：
 * 这个位置承载的是列表项主文本（设置项标题、账号名），
 * M3 列表项主文本在 56dp 及以上的高度下用 Body Large 才符合规格。
 */
@Composable
fun LabelMain(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        color = color
    )
}

/**
 * 次级说明文本（替代 Miuix 的 onSurfaceVariantSummary）。
 *
 * 用 Body Small（12sp / 400）配 onSurfaceVariant 颜色：
 * M3 规定小号文本对比度需 ≥4.5:1，onSurfaceVariant 在中性表面上满足该条件。
 */
@Composable
fun LabelSummary(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

// ---------------------------------------------------------------- 卡片

/**
 * 填充式卡片（替代 Miuix 的 Card）。
 *
 * 【2026-11-03 与 PcCard 对齐】改用 surfaceContainerLow + 细描边，不用 elevation。
 *   原因见 PcCard 的注释：elevation 的 1dp 阴影在深色模式与低对比屏上不可靠，
 *   而本应用大量并置卡片，需要稳定可见的边界。两套卡片组件必须同款，
 *   否则同一屏里会出现"有的卡有边、有的卡没边"。
 *
 * 圆角 12dp、内边距 16dp 均为 M3 规定值。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(SpaceL),
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpaceL),
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = SpaceM)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(SpaceM)) {
                content()
            }
        }
    }
}

/**
 * 描边卡片 —— 用于需要与背景明确分开、但不应有浮起感的场景（如统计数字块）。
 */
@Composable
fun AppOutlinedCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.outlinedCardColors()
    ) {
        Column(modifier = Modifier.padding(SpaceL)) { content() }
    }
}

/**
 * 卡片内的一行：左侧标题 + 可选副标题，右侧可选值文字。
 *
 * 触控目标高度用 TouchMin(48dp) 兜底 —— M3 的 list item 规格是
 * 56/72/88dp，但这里的行很多是纯展示无点击，48dp 是安全下限。
 */
@Composable
fun AppRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null
) {
    val base = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickableRow(onClick) else Modifier)
        .padding(vertical = SpaceM)

    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            LabelMain(title)
            if (summary != null) {
                LabelSummary(summary, Modifier.padding(top = 2.dp))
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SpaceM)
            )
        }
    }
}

/**
 * 纯信息行（无点击、无副标题），用于「关于」这类静态展示。
 */
@Composable
fun AppInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SpaceM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LabelMain(label, Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- 工具

/**
 * 可点击修饰符 —— 抽出来避免在每个组件里重复写 ripple 配置。
 */
@Composable
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(
        onClick = onClick,
        indication = ripple(),
        interactionSource = remember { MutableInteractionSource() }
    )
