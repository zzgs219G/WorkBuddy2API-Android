package com.dsh.aihub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 积分详情弹窗 —— 按账号查看积分来源与去向。
 *
 * 数据由三部分组成（详见 NativeCore.creditDetail）：
 *   ① 上游实时余额 + 资源包拆分
 *   ② 本地账本汇总（签到 / 奖励 / 消耗分列）
 *   ③ 本地账本明细
 *
 * 为什么必须区分"签到"和"奖励"两类：
 *   上游只提供当前余额快照，没有任何流水接口。若不本地分类记账，
 *   用户看到的永远只是一个总数，无法回答"签到到底给没给分"。
 *
 * 【M3 移植】Miuix 的 WindowDialog 无条件渲染语义（show 参数在内部生效），
 *   M3 的 AlertDialog 改由外层 if 控制。此处调用点（AccountsScreen）已在
 *   if (showCreditDetail) 内渲染，所以弹窗自身不需要再包一层。
 *   原自由布局的三个动作（刷新 / 清空账本 / 关闭）落到具名插槽：
 *   主要操作「关闭」放 confirmButton，「刷新」「清空账本」放 dismissButton 一行。
 *   注意「清空账本」是破坏性操作，但它是原布局里的次要按钮之一，
 *   这里保持原有语义与位置，不自行升级为强调样式。
 * 【M3 移植】滚动容器保留：内容段数由上游 JSON 决定，可能很长，
 *   heightIn(max) + verticalScroll 的原策略不变。
 */
@Composable
fun CreditDetailDialog(
    accountName: String,
    detail: JSONObject?,
    onClearLedger: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("积分详情 · $accountName") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                if (detail == null) {
                    Text("读取中…", style = MaterialTheme.typography.bodyLarge)
                } else {
                    BalanceSection(detail)
                    Spacer(Modifier.height(PcTokens.SpaceM))
                    LedgerSummarySection(detail)
                    Spacer(Modifier.height(PcTokens.SpaceM))
                    PackagesSection(detail)
                    Spacer(Modifier.height(PcTokens.SpaceM))
                    LedgerEntriesSection(detail)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        // 三个动作：刷新（重新拉上游）、清空账本（重置本地统计）、关闭
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                TextButton(onClick = onRefresh) { Text("刷新") }
                TextButton(onClick = onClearLedger) { Text("清空账本") }
            }
        }
    )
}

/** ① 实时余额段。 */
@Composable
private fun BalanceSection(detail: JSONObject) {
    Text(
        "当前余额",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(PcTokens.SpaceS / 2))
    val remaining = detail.optDouble("remaining", 0.0)
    val total = detail.optDouble("total", 0.0)
    PcInfoRow("剩余 / 总额度", "${"%.1f".format(remaining)} / ${"%.1f".format(total)}")
    PcInfoRow("已消耗", "%.1f".format((total - remaining).coerceAtLeast(0.0)))
    val expire = if (detail.isNull("expire_at")) null else detail.optDouble("expire_at", 0.0)
    PcInfoRow("最早到期", expire?.let { formatTime(it.toLong()) } ?: "无期限信息")
    val checkedToday = detail.optString("last_checkin_date").take(10) ==
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    PcInfoRow("今日签到", if (checkedToday) "已签到" else "未签到")
    // 上游刷新失败时如实提示，避免用户以为看到的是最新值
    val refreshError = detail.optString("refresh_error")
    if (refreshError.isNotBlank()) {
        Spacer(Modifier.height(PcTokens.SpaceS / 2))
        Text(
            "上游刷新失败，以下为本地缓存值：$refreshError",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** ② 账本汇总段：今日 / 累计，按来源分列。 */
@Composable
private fun LedgerSummarySection(detail: JSONObject) {
    val summary = detail.optJSONObject("ledger_summary") ?: return
    Text(
        "积分来源（本地记账）",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(PcTokens.SpaceS / 2))
    PcInfoRow(
        "今日获得 / 消耗",
        "%.1f / %.1f".format(summary.optDouble("today_gain"), summary.optDouble("today_loss"))
    )
    PcInfoRow(
        "累计获得 / 消耗",
        "%.1f / %.1f".format(summary.optDouble("total_gain"), summary.optDouble("total_loss"))
    )
    val kinds = summary.optJSONObject("kinds") ?: return
    Spacer(Modifier.height(PcTokens.SpaceS / 2))
    // 固定顺序输出四类来源，避免 JSON 键序不稳定导致每次展示顺序都变
    val labels = listOf(
        "checkin" to "每日签到",
        "task" to "任务奖励",
        "gift" to "新手礼包",
        "compensation" to "活动补偿"
    )
    var shown = false
    for ((key, label) in labels) {
        val item = kinds.optJSONObject(key) ?: continue
        shown = true
        PcInfoRow(
            label,
            "今日 %.1f｜累计 %.1f".format(item.optDouble("today"), item.optDouble("total"))
        )
    }
    if (!shown) {
        Text(
            "暂无记账。签到或领奖产生积分变动后，这里会按来源分类统计。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** ③ 资源包段：把总额度拆开，看每笔发放的余量与到期。 */
@Composable
private fun PackagesSection(detail: JSONObject) {
    val packages = detail.optJSONArray("packages") ?: return
    if (packages.length() == 0) return
    Spacer(Modifier.height(PcTokens.SpaceS))
    Text("额度构成（${packages.length()} 个资源包）",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(PcTokens.SpaceS / 2))
    // 按「到期紧迫度」排序，最快的排最前 —— 这是用户最该先看到的，
    // 因为快到期的积分如果不优先消耗就会白白作废。
    val sorted = (0 until packages.length())
        .mapNotNull { packages.optJSONObject(it) }
        .sortedBy { p ->
            if (p.isNull("expire_at")) Double.MAX_VALUE else p.optDouble("expire_at", 0.0)
        }
    val nowSec = System.currentTimeMillis() / 1000.0
    for (p in sorted) {
        val remain = p.optDouble("remaining", 0.0)
        val totalP = p.optDouble("total", 0.0)
        // 统一 schema：kind 由 NativeCore 投影好的 is_cycle 决定，
        // 不再在界面侧读上游字段名（CycleCapacitySize）—— 那正是之前
        // 到期时间读不到、字段名靠猜的同一类问题。
        val kind = p.optString("label").ifBlank {
            if (p.optBoolean("is_cycle", false)) "周期额度" else "总额度"
        }
        // expire_at 由 NativeCore.expirySecondsOf 算好：已处理 DeductionEndTime
        // 优先、哨兵值（>730 天视为不过期）过滤、秒/毫秒单位归一。
        // 这里不再自己解析字段名 —— 那正是之前读不到到期时间的原因。
        val expiry = if (p.isNull("expire_at")) null else p.optDouble("expire_at", 0.0)
        val daysLeft = expiry?.let { (it - nowSec) / 86400.0 }
        // 三重状态：已过期 / 临期（7 天内）/ 正常
        val (hint, hintColor) = when {
            daysLeft == null -> "不过期" to MaterialTheme.colorScheme.onSurfaceVariant
            daysLeft < 0 -> "已过期" to MaterialTheme.colorScheme.error
            daysLeft <= 7 -> "剩 ${daysLeft.toInt()} 天，优先消耗" to MaterialTheme.colorScheme.error
            else -> "剩 ${daysLeft.toInt()} 天" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        // 只用余量的包不必占一整行，直接跳过展示噪声
        Text(
            "$kind：${"%.1f".format(remain)} / ${"%.1f".format(totalP)}　$hint",
            style = MaterialTheme.typography.bodySmall,
            color = if (daysLeft != null && daysLeft <= 7) hintColor
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
    }
    Spacer(Modifier.height(PcTokens.SpaceS / 2))
    Text(
        "快到期的资源包会被优先选中，避免积分作废。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** ④ 账本明细段：逐笔列出，最多显示最近 30 条。 */
@Composable
private fun LedgerEntriesSection(detail: JSONObject) {
    val ledger = detail.optJSONArray("ledger") ?: return
    Spacer(Modifier.height(PcTokens.SpaceS))
    Text("明细（最近 ${minOf(ledger.length(), 30)} 条）",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(PcTokens.SpaceS / 2))
    if (ledger.length() == 0) {
        Text(
            "暂无记录。账本只记录确实算出资数额的变动（签到不发分时不会留下 0 值记录）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    for (i in 0 until minOf(ledger.length(), 30)) {
        val e = ledger.optJSONObject(i) ?: continue
        val amount = e.optDouble("amount", 0.0)
        val sign = if (amount >= 0) "+" else "−"
        val kindLabel = when (e.optString("kind")) {
            "checkin" -> "签到"
            "task" -> "任务"
            "gift" -> "礼包"
            "compensation" -> "补偿"
            "consume" -> "消耗"
            else -> e.optString("kind")
        }
        Text(
            "${fmt.format(Date((e.optDouble("ts", 0.0) * 1000).toLong()))}　$kindLabel　" +
                "$sign${"%.1f".format(kotlin.math.abs(amount))}" +
                (e.optString("note").takeIf { it.isNotBlank() }?.let { "　$it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (amount >= 0) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(2.dp))
    }
}

/** 时间戳（秒）→ 本地可读时间。兼容秒/毫秒两种上游返回。 */
private fun formatTime(raw: Long): String {
    val seconds = if (raw > 10_000_000_000L) raw / 1000 else raw
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(seconds * 1000))
}
