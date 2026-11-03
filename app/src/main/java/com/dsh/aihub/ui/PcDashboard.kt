package com.dsh.aihub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dashboard 层 —— 「像后端控制台」的那一层视觉语言。
 *
 * 与 [PcComponents] 的分工：
 *   · PcComponents 是**容器与行**的基元（卡片、标签值行、按钮组），语义中性；
 *   · 本文件是**数据展示**的基元（指标卡、进度条、排行行、状态点）。
 *
 * 为什么需要单独一层：此前的概览页把每个数字都压成 `bodySmall` 的一行小字
 * （`今日 8 次 · 12K tokens`），读者必须逐字读完才知道重点在哪 ——
 * 这是"文字报告"，不是"仪表盘"。仪表盘靠**排版层级**传递优先级：
 *   数值（大、重、onSurface）> 单位/标签（小、onSurfaceVariant）> 说明（更小、更淡）。
 *
 * M3 用法约定（全部用官方角色，不写死颜色）：
 *   · 指标块底 = surfaceContainerHighest（比卡片再深一档，形成"格子"感）
 *   · 进度条底 = surfaceContainerHighest，填充 = primary
 *   · 状态点 = primary / error / onSurfaceVariant 三态
 *   这样深浅色两套主题自动成立，不需要为每处单独判断 isSystemInDarkTheme。
 */
object DashTokens {
    /** 指标块圆角：比卡片（12dp）小一档，视觉上才是"卡片里的格子"而不是"又一张卡片"。 */
    val CellRadius = 10.dp

    /** 指标块内边距。横向 12dp 够放下 "12.3k" 这种 5 字符数值而不换行。 */
    val CellPaddingH = 12.dp
    val CellPaddingV = 10.dp

    /** 指标数值字号。headlineSmall 是 24sp —— 仪表盘主数字就该这么大。 */
    val MetricValueSize = 24.sp

    /** 条形图高度。4dp 是 M3 progress indicator 的细档，够看比例又不占空间。 */
    val BarHeight = 4.dp
}

// ---------------------------------------------------------------- 指标网格

/** 一个指标格子的数据。 */
data class DashMetric(
    val label: String,
    val value: String,
    /** 单位/后缀，跟在大数字后面用弱化样式（如 "积分"、"次"）。 */
    val unit: String? = null,
    /** 副说明，一格小字（如 "较昨日 +12"、"3 个账号"）。 */
    val hint: String? = null,
    /** 数值颜色。默认 onSurface；异常/负值可传 error。 */
    val valueColor: Color? = null,
    /** 可点击（有更细一级的页面）。 */
    val onClick: (() -> Unit)? = null,
    /** 用主色强调（如"剩余积分"是这一屏最该看的）。 */
    val emphasize: Boolean = false,
)

/**
 * 一行指标格子（每格等宽，格子之间留 8dp）。
 *
 * 为什么不用 Card 而用带底色的 Box：
 *   四个指标挤在一张卡片里，如果再各自套 Card，就是"卡中卡"，边框叠加会很脏。
 *   这里用 surfaceContainerHighest 的圆角矩形做格子，既区分了每个指标，
 *   又保持在同一张父卡片的空间内 —— 这是 dashboard 的常规做法。
 *
 * @param perRow 每行几格。指标语义上等价的放一行（如 请求/成功/失败），
 *               不等价的另起一行，宁可多一行也不要把无关数字并排让人误比。
 */
@Composable
fun DashMetricGrid(
    metrics: List<DashMetric>,
    perRow: Int = 2,
    modifier: Modifier = Modifier,
) {
    if (metrics.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
        metrics.chunked(perRow.coerceAtLeast(1)).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS),
            ) {
                row.forEach { metric ->
                    DashMetricCell(metric, Modifier.weight(1f))
                }
                // 最后一行不满时补等宽占位，避免剩下的格子被拉宽到与上方密度不一致
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 单个指标格子。 */
@Composable
fun DashMetricCell(metric: DashMetric, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    val valueColor = metric.valueColor ?: if (metric.emphasize) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val base = modifier
        .clip(RoundedCornerShape(DashTokens.CellRadius))
        .background(bg)
        .then(if (metric.onClick != null) Modifier.clickable { metric.onClick.invoke() } else Modifier)
        .padding(horizontal = DashTokens.CellPaddingH, vertical = DashTokens.CellPaddingV)

    Column(modifier = base) {
        Text(
            text = metric.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = metric.value,
                fontSize = DashTokens.MetricValueSize,
                // 数值用 Medium 而不是 SemiBold：24sp 已经很重，
                // 再 Bold 会和上方 label 的层级差拉得过大，粗细对比反而变糊。
                fontWeight = FontWeight.Medium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metric.unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = metric.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp), // 与数字基线对齐
                )
            }
        }
        if (metric.hint != null) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = metric.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- 状态点

/** 状态语义（决定颜色，不带文案）。 */
enum class DashStatus { OK, WARN, BAD, IDLE }

@Composable
fun dashStatusColor(status: DashStatus): Color = when (status) {
    DashStatus.OK -> MaterialTheme.colorScheme.primary
    DashStatus.WARN -> MaterialTheme.colorScheme.tertiary
    DashStatus.BAD -> MaterialTheme.colorScheme.error
    DashStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 状态点 + 文案。
 *
 * 用点而不是只用彩色文字：色盲用户区分不了「绿色成功/红色失败」两种文字色，
 * 但点的位置（行首）能提供第二个信号通道。这是 M3 状态展示的通行做法。
 */
@Composable
fun DashStatusChip(
    status: DashStatus,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dashStatusColor(status))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = dashStatusColor(status),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 比例条

/**
 * 细比例条。
 *
 * 用途：额度余量、某账号消耗占全池的比例。
 * `fraction` 会被裁到 0..1 —— 上游偶尔给出 remain > total（活动赠送），
 * 不裁会让条溢出到卡片外。
 */
@Composable
fun DashBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    height: androidx.compose.ui.unit.Dp = DashTokens.BarHeight,
) {
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (safe > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(safe)
                    .height(height)
                    .clip(RoundedCornerShape(height / 2))
                    .background(color ?: MaterialTheme.colorScheme.primary)
            )
        }
    }
}

// ---------------------------------------------------------------- 排行行

/**
 * 排行行：左名称 + 右数值 + 可选比例条。
 *
 * 这是「谁消耗了多少」的落点 —— 一行一个账号，数值右对齐成列，
 * 上下扫一眼就能比出大小，比每账号一张卡省一半高度且更易比较。
 *
 * @param fraction 相对全池最大值的比例（用于条形图）；null 则不画条
 */
@Composable
fun DashRankRow(
    name: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    fraction: Float? = null,
    status: DashStatus? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick.invoke() } else Modifier)
            .padding(vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status != null) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dashStatusColor(status))
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(PcTokens.SpaceS))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                // 数值列用等宽字体：不同位数的数字右对齐后能对齐个位，
                // 扫一眼就能比大小。这是数字表格的基本要求。
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (status != null) 12.dp else 0.dp),
            )
        }
        if (fraction != null) {
            Spacer(Modifier.height(4.dp))
            DashBar(
                fraction = fraction,
                modifier = Modifier.padding(start = if (status != null) 12.dp else 0.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 分区标题

/**
 * 带右侧操作的分区标题（dashboard 风格）。
 *
 * 与 m3.SectionTitle 的区别：这个支持右侧放一个次要动作（如「查看全部」），
 * 且字号更大一档 —— 仪表盘里分区标题需要明显强于卡片内的 label。
 */
@Composable
fun DashSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = PcTokens.PageGutter + PcTokens.SpaceS,
                end = PcTokens.PageGutter,
                top = PcTokens.SpaceM,
                bottom = PcTokens.SpaceS,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) action()
    }
}

/** 卡片内的分节标签（比卡片标题轻、比正文重），用于一张卡片里塞两组数据。 */
@Composable
fun DashGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 空状态占位（统一文案样式，避免每处各写一段 bodySmall）。 */
@Composable
fun DashEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 卡片内的紧凑说明文字。 */
@Composable
fun DashCaption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一行紧凑的「标签 数值」组合，等分宽度并右对齐数值。
 *
 * 与 [DashMetricGrid] 的区别：这里**不放大数字**，用在"次级事实"上
 * （存储页的子目录占用、协议分布的计数等）。它替代原先的 PcInlineFacts ——
 * 那个函数把所有项拼成一句 bodySmall 文本，"WebView 缓存 1.2MB · cache 800KB"
 * 需要逐字读完才知道有几项，而且项与项之间没有对齐，无法纵向扫读。
 *
 * @param showBar true 时给每项加一条相对最大值的比例条（三级维度合一）。
 */
@Composable
fun DashFacts(
    parts: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    if (parts.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS),
    ) {
        parts.forEach { (label, value) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
