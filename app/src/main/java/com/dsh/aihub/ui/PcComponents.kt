package com.dsh.aihub.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 布局基元 —— 全 App 统一的间距与卡片样式来源。
 *
 * 【Material 3 移植说明】
 *   本文件原先是「Miuix 布局基元」，是所有页面的公共依赖（PcCard/PcRow/PcDivider/
 *   PcInfoRow/PcMonoRow/PcActionGrid/PcTokens/PcAction）。
 *   移植到 Material 3 时**只替换了组件实现，没有改任何布局意图** ——
 *   下面所有关于间距/对齐/折行策略的注释都是原作者踩坑后的判断，逐条保留。
 *
 * 为什么单独抽这一层，以及各值为什么是这些数：
 *   Miuix 的 Card 默认 insideMargin 是 0.dp（见 CardDefaults.InsideMargin），
 *   若不统一给内边距，内容会紧贴卡片边缘，视觉上"全糊在一起"。
 *   把间距收在这里而不是各页面自写，改一处即可全局生效。
 *
 * 内边距单一来源原则：
 *   卡片的左右内边距【只由 PcCard 的 insideMargin 提供】，卡片内层不再加左右 padding；
 *   行只负责彼此的垂直间距。否则会出现"卡片 16dp + 行再 8dp = 24dp"的双重缩进。
 */
object PcTokens {
    /** 页面左右边距（与 Miuix 官方示例一致）。 */
    val PageGutter = 12.dp

    /** 卡片之间的垂直间隔。 */
    val CardGap = 12.dp

    /** 卡片与上方小标题之间的间隔（原为 0，导致标题贴在卡片上）。 */
    val TitleGap = 8.dp

    /** 卡片内部的行间距。 */
    val SpaceS = 10.dp
    val SpaceM = 14.dp
    val SpaceL = 16.dp
}

/**
 * 统一卡片容器。
 *
 * 【M3 移植】用 ElevatedCard（elevation 1dp）而不是 filled Card：
 *   M3 的 filled card 在浅色背景下与 surface 几乎无区分，
 *   而本应用是「浅色大背景 + 多张同色卡片」的布局，需要卡片有可辨识的浮起感。
 *   圆角 12dp 是 M3 官方规格值。
 *
 * @param title 卡片标题（非空时渲染标题，并与卡片保持 [PcTokens.TitleGap] 间隙）
 */
@Composable
fun PcCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    insideMargin: PaddingValues = PaddingValues(
        horizontal = PcTokens.SpaceL,
        vertical = PcTokens.SpaceM
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            // 卡片标题：M3 的 Title Small（14sp/500）。
            // 不用 Headline 级别 —— 这个位置是分组标题，压过页面主标题会破坏层级。
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = PcTokens.PageGutter,
                    end = PcTokens.PageGutter,
                    bottom = PcTokens.TitleGap
                )
            )
        }
        // innerPadding 用 Modifier.padding 而非 Card 的 contentPadding 参数：
        // Material3 的 Card 没有 insideMargin 概念，内容 padding 只能自己加。
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.elevatedCardColors(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(insideMargin),
                content = content
            )
        }
    }
}

/**
 * 卡片内的一行：左主文 + 右值/动作。
 *
 * 行只加垂直 padding；左右一律不缩进，水平方向交给外层 [PcCard] 的 insideMargin。
 */
@Composable
fun PcRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val base = Modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS / 2)
    if (onClick != null) {
        // 可点击行必须有「可点击」的视觉信号，否则用户看到一排同样
        // 样式的文字，分不清哪个能点。
        //
        // 三个信号一起用，缺一都会让某些用户看不出来：
        //   ① 右侧箭头 ›  —— 静态可见，扫一眼就知道这行有下一级
        //   ② 水波纹     —— 点下去有反馈，确认点击被接收
        //   ③ 承载层     —— 点击区域覆盖整行（含右侧留白），不是只有文字
        //
        // 不可点击行（纯展示）不画箭头，也不响应点击 —— 两者状态就分开了。
        Column(
            modifier = base.clickable(
                onClick = onClick,
                role = Role.Button,
            )
        ) {
            PcRowInner(title, summary, value, valueColor, trailing, clickable = true)
        }
    } else {
        Column(modifier = base) {
            PcRowInner(title, summary, value, valueColor, trailing, clickable = false)
        }
    }
}

/** 行的视觉主体（不负责点击，供上面两种分支共用）。 */
@Composable
private fun PcRowInner(
    title: String,
    summary: String?,
    value: String?,
    valueColor: Color,
    trailing: @Composable (() -> Unit)?,
    clickable: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                // M3 的 Body Large（16sp/400）：这是列表项主文本，
                // 在 48dp+ 的行高下用 Body Large 才符合 M3 列表项规格。
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    // M3 的 Body Small（12sp/400）+ onSurfaceVariant。
                    // M3 规定小号文本对比度需 ≥4.5:1，该配色在中性表面上满足。
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(PcTokens.SpaceM))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (valueColor == Color.Unspecified) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    valueColor
                }
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(PcTokens.SpaceM))
            trailing()
        }
        // 可点击行统一画一个 › 作为「有下一级」的静态信号。
        //
        // 用 Text 画而不是 Icon：M3Icons 的内联 ImageVector 在 release
        // 包里渲染不出来（见 v1.2.5/v1.2.6 记录），文字方案没有这个风险，
        // 而且 › 本身就是最常见的引导符。
        if (clickable) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 卡片内分割线。
 *
 * 【M3 移植】Miuix 的 HorizontalDivider 自带 margin，Material3 的 HorizontalDivider
 * 没有额外 margin —— 所以原先「取消上下 padding 避免双重缩进」的处理现在天然成立，
 * 使用方需要间距时自行加 Spacer（与原约定一致）。
 */
@Composable
fun PcDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.fillMaxWidth())
}

/** 纵向留白。 */
@Composable
fun PcGap(height: androidx.compose.ui.unit.Dp = PcTokens.CardGap) {
    Spacer(Modifier.height(height))
}

/**
 * 键值对展示行（只读）。
 *
 * 布局要点（此前踩坑）：标签列用 weight 会与长值争抢宽度，导致短标签
 * （如 "UID"）被压成逐字竖排。改为「标签按内容自适应、值占剩余宽度」，
 * 并给标签设最小宽度，保证同一卡片内多行左对齐、视觉成列。
 */
@Composable
fun PcInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 固定标签列宽：保证卡片内各行标签左对齐成列，且短标签不会被压成竖排。
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(PcTokens.SpaceS))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            // 值占剩余宽度并允许换行（长 UID、长 URL 都需要）
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 一排等宽的指标块：大字号数值 + 小字号标签。
 *
 * 为什么需要它
 * ------------
 * 原先每个数字都占一整行「标签 : 值」：
 *
 *     账号            2 个
 *     模型            18
 *     全部剩余额度  7576 积分
 *
 * 问题有两个：
 *   ① 标签列固定 96dp，加上值与行内边距，一半屏宽是空白；
 *      六个数字就要 240dp 高度，而它们的信息量远不到这个尺寸。
 *   ② 六个数字权重完全相同，用户看不出哪个该先看。
 *
 * 指标块的解法是把「同维度、同量级的小数字」横向排一行：数值放大到
 * headlineSmall 建立视觉重心，标签降为 labelSmall，宽度等分。
 * 一行放 3 个时，六个指标只需两行、约 110dp —— 密度上去，层次也有了。
 *
 * 适用边界：只放**短数值**（个数、积分数、次数）。
 * 路径、Key、UID 这类长值不要塞进来 —— 它们需要逐字符比对，
 * 塞进等分格子只会被截断，反而更难读，那些继续用 [PcInfoRow]。
 */
@Composable
fun PcMetricRow(
    items: List<Pair<String, String>>,
    /** 第几项可点击（-1 表示都不可点）。可点项用主题色，暗示"点进去有内容"。 */
    clickableIndex: Int = -1,
    onClick: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        items.forEachIndexed { index, (label, value) ->
            val clickable = index == clickableIndex && onClick != null
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (clickable) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick!!() } else Modifier)
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = value,
                    // headlineSmall(24sp)：指标是这一屏的视觉重心，
                    // 用 bodyLarge 会和普通行的值分不出来。
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (clickable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 不足 3 个时补占位，避免剩余两项被拉宽到与三项不同的密度
        repeat(3 - items.size.coerceAtMost(3)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * 一行紧凑的「键 值 · 键 值」文本。
 *
 * 用于「同类别但凑不满一排」的零散指标：与其各占一整行，
 * 不如压成一行小字。例如：
 *
 *     今日  8 次 · 12K tokens
 *
 * 之所以不做成指标块：这类值量级不一致（次数 vs tokens），
 * 并排放大反而让人误以为可以直接比较。
 */
@Composable
fun PcInlineFacts(parts: List<Pair<String, String>>) {
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  ·  ") { (label, value) -> "$label $value" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 可点击复制的信息行。
 *
 * 为什么做成"点值本身复制"而不是旁边放一个按钮：
 *   原先给局域网 baseUrl 配了一个整宽的圆角按钮「复制局域网 baseUrl」——
 *   它占掉一整行高度、视觉权重比信息本身还高，而复制是一个从属动作。
 *   更符合直觉的交互是**点这个值就复制**（同系统设置里点 Wi-Fi 名称复制）。
 *
 * 交互细节：
 *   · 整行可点（label 也算），因为值本身可能很长，只点文字容易点空
 *   · 用 [combinedClickable]：点按复制、长按也复制（长按在部分 ROM 上更稳）
 *   · 复制后由调用方给出提示（本组件不弹 toast，避免与页面级提示重复）
 *   · 保留 Ripple：没有反馈的点击会让人以为没生效
 */
@Composable
fun PcCopyableRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCopy(value) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(PcTokens.SpaceS))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 等宽值展示行：用于 UID / Key / URL 这类需要逐字符比对的字段。
 * 不换行而是横向滚动，避免长 UID 折成两行破坏可读性。
 */
@Composable
fun PcMonoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(PcTokens.SpaceS))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 卡片内动作按钮组的统一布局。
 *
 * 为什么需要它：按钮默认 minWidth/minHeight 较大，直接放进 Row 而不给间距时，
 * 相邻按钮会紧贴成一坨（用户反馈"功能键融一块"）；
 * 而按钮数量超过 3 个又硬塞一行，会把文字压到换行。
 *
 * 这里统一为：最多每行 2 个，等宽平分，行间与列间都有固定间距。
 * 传入的 actions 按顺序自动折行。
 *
 * 【M3 移植注意】Material3 的 TextButton 没有 `text =` 参数（Miuix 有），
 * 内容要作为尾随 lambda 传入。这是本次移植里唯一的签名差异。
 *
 * @param actions 每个元素是 (文案, 是否主按钮, 是否可用, 点击)
 */
@Composable
fun PcActionGrid(
    actions: List<PcAction>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)
    ) {
        // chunked(2)：每行最多两个。3 个按钮时最后一行只有一个，用 weight(1f) + 空占位
        // 把它留在左侧，避免单个按钮被拉成整行宽（视觉上像另一个标题）。
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)
            ) {
                row.forEach { action ->
                    if (action.primary) {
                        Button(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f)
                        ) { Text(action.label) }
                    } else {
                        TextButton(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f)
                        ) { Text(action.label) }
                    }
                }
                // 奇数个时补一个不可见的等宽占位，保持按钮宽度一致
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** [PcActionGrid] 的动作描述。 */
data class PcAction(
    val label: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)
