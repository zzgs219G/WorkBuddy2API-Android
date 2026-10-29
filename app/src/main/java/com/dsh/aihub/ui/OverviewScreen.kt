package com.dsh.aihub.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.aihub.ApiHostService
import com.dsh.aihub.FormatKit
import com.dsh.aihub.LanAddress
import com.dsh.aihub.m3.SectionTitle

/**
 * 概览页 —— 对应原界面 overviewPage()。
 *
 * 保留原页面的信息层次（服务状态 → 快速概览 → 账号额度 → 开始使用 → 最近活动），
 * 但合并了原「更多」页里的用量入口，让状态与统计在一屏内可见。
 */
@Composable
fun OverviewScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    val activity = LocalContext.current as? android.app.Activity
    // 必须从 AppState 读（它是 mutableStateOf，广播写入后会触发重组）。
    // 直接读 ApiHostService.running 不会建立订阅，按钮状态不会更新。
    val running = app.serviceRunning
    val starting = app.startingUp

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ---------------- 服务状态 ----------------
        item {
            PcCard(title = "本地网关") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        when {
                            running -> "●  运行中"
                            starting -> "◐  启动中…"
                            else -> "○  已停止"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            running -> MaterialTheme.colorScheme.primary
                            starting -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        when {
                            running -> "健康"
                            starting -> "预热中"
                            else -> "离线"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(PcTokens.SpaceS))
                // 两种地址都给，因为它们各自的适用场景不同，缺一都会让人连不通：
                //
                //   127.0.0.1  → 只有**手机本机**的 App 能用。填到电脑上无效，
                //                因为 127.0.0.1 在电脑上指的是电脑自己。
                //   192.168.x  → **同局域网的其它设备**（电脑/平板）必须用这个。
                //
                // 之前开局域网后只显示 192 版，手机上其它 App 反而拿不到回环地址；
                // 更早则固定只显示 127，电脑上填了永远连不通。
                val lanEnabled = app.settings.optString("lanAccess", "0") in setOf("1", "true")
                val lanEntry = remember(lanEnabled) { if (lanEnabled) LanAddress.primary() else null }
                val loopbackUrl = "http://127.0.0.1:${ApiHostService.PORT}/v1"
                Text(
                    when {
                        starting -> "正在校验账号并预热模型，通常数秒内完成；若长时间无变化，请检查通知权限。"
                        running && lanEntry != null -> "本机与局域网都可调用"
                        running -> "仅本机可调用（局域网未开启）"
                        else -> "启动后仅在本机提供 OpenAI 兼容 API。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 运行中才显示可复制的地址：没启动时复制一个连不通的地址没有意义。
                if (running) {
                    PcCopyableRow("本机地址", loopbackUrl) {
                        copyToClipboard(context, it)
                        app.showMessage("已复制本机地址：$it")
                    }
                    if (lanEntry != null) {
                        PcCopyableRow("局域网地址", LanAddress.baseUrl(ApiHostService.PORT, lanEntry)) {
                            copyToClipboard(context, it)
                            app.showMessage("已复制局域网地址：$it")
                        }
                    }
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                // 两个都是页面级操作，都放在顶部这张卡里。
                //
                // 「刷新全部」原先被放在页面最底部的一张「开始使用」卡里，
                // 于是每次刷新都要先滚到最下面 —— 而它是这个页面最高频的动作。
                // 页面级主操作应该在第一屏可见处。
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(
                        // 启动中仍可点「停止」：若陷入启动异常（如上游卡住），
                        // 用户必须有办法中止，否则只能杀进程 —— 此前 disabled = !starting
                        // 会把按钮锁死，是"反复启停后再也停不掉"的观感来源之一。
                        onClick = { app.toggleServer(activity) },
                        modifier = Modifier.weight(1f)
                    ) { Text(when { running -> "停止服务"; starting -> "取消启动"; else -> "启动服务" }) }
                    TextButton(
                        onClick = { app.refreshAll() },
                        enabled = !app.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (app.busy) "刷新中…" else "刷新全部") }
                }
            }
        }

        // ---------------- 快速概览 ----------------
        //
        // 布局从「六行标签:值」改为「一排指标块 + 一行零散指标」。
        //
        // 原来的六行各占一整行、标签列固定 96dp，一半屏宽是空白，且六个数字
        // 权重完全相同 —— 既浪费垂直空间，也建立不起主次。
        //
        // 现在按「用户最关心什么」分两层：
        //   · 第一层（指标块）：剩余积分 / 模型数 / 账号数。这三个决定了
        //     "还能不能用、能用多少"，是打开 App 第一眼要看的。
        //   · 第二层（一行小字）：今日请求与 Tokens。它们量级不同（次数 vs tokens），
        //     并排放大反而会被误以为可直接比较，压成一行小字更合适。
        //   · 运行记录条数从概览移走 —— 那是调试信息，出现频率低，
        //     需要时去「记录」页看即可。
        item {
            PcCard(title = "快速概览") {
                val creditKnown = app.credits?.optInt("known") ?: 0
                PcMetricRow(
                    listOf(
                        "剩余积分" to
                            if (creditKnown > 0) FormatKit.credits(app.credits?.optDouble("remain") ?: 0.0)
                            else "—",
                        "模型" to (app.models?.length()?.toString() ?: "—"),
                        "账号" to app.accounts.length().toString(),
                    ),
                    // 「模型」这一项可点：模型清单不再是独立 Tab，从这里进入。
                    // 其余两项不可点 —— 积分没有更细的页面，账号数量对应的是
                    // 隔壁 Tab 已经能直接到达的地方，重复入口反而增加困惑。
                    clickableIndex = 1,
                    onClick = { onOpenSub(SubPage.MODELS) },
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                PcInlineFacts(
                    listOf(
                        "今日" to "${FormatKit.tokens(app.usageSummary?.optLong("today_requests") ?: 0)} 次",
                        "Tokens" to FormatKit.tokens(app.usageSummary?.optLong("today_tokens") ?: 0),
                    )
                )
                // 临期额度提示。
                //
                // 为什么必须在概览页：此前到期信息只在「账号 → 积分详情」里，
                // 而进积分详情要先点账号卡、再等它刷新 —— 等于「我想到期情况
                // 得一个个翻账号」，这就是用户反馈的原话。概览是第一屏，
                // 「有多少积分快作废了」属于应该一眼看到的信息。
                //
                // 只统计还有余量且不是哨兵值（NativeCore 已把 >730 天判为 null）的包。
                val expiringSoon = remember(app.accounts, app.revision) {
                    var soon = 0.0
                    var soonCount = 0
                    val nowSec = System.currentTimeMillis() / 1000.0
                    for (i in 0 until app.accounts.length()) {
                        val acc = app.accounts.optJSONObject(i) ?: continue
                        if (acc.isNull("credits_expire_at")) continue
                        val remain = if (acc.isNull("credits_remaining")) 0.0
                        else acc.optDouble("credits_remaining", 0.0)
                        if (remain <= 0) continue
                        val days = (acc.optDouble("credits_expire_at", 0.0) - nowSec) / 86400.0
                        if (days in 0.0..7.0) { soon += remain; soonCount++ }
                    }
                    soon to soonCount
                }
                if (expiringSoon.second > 0) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(
                        "⚠ ${"%.0f".format(expiringSoon.first)} 积分将在 7 天内到期" +
                            "（${expiringSoon.second} 个账号），这些账号会被优先使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // ---------------- 今日收支 ----------------
        //
        // 用户原话：「我并不知道我今天花了多少积分，只能刷新一下看积分少了多少」。
        // 数据一直在：ApiHostService 每次调用都往 credit_ledger 写一条
        // kind="consume" 的负数（ApiHostService:679），creditLedgerSummary 甚至
        // 早就算出了 today_loss —— 只是从来没在概览页聚合展示过。
        item {
            val flow = app.creditFlow
            PcCard(title = "今日收支") {
                if (flow == null) {
                    Text("加载中…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val gain = flow.optDouble("today_gain", 0.0)
                    val loss = flow.optDouble("today_loss", 0.0)
                    val net = flow.optDouble("net", 0.0)
                    PcMetricRow(
                        listOf(
                            "获得" to "+${"%.0f".format(gain)}",
                            "消耗" to "-${"%.0f".format(loss)}",
                            "净变化" to (if (net >= 0) "+" else "") + "%.0f".format(net),
                        )
                    )
                    // 按来源拆开：只给一个「消耗 -82」无法回答"钱花在哪了"。
                    val kinds = flow.optJSONObject("by_kind")
                    if (kinds != null && kinds.length() > 0) {
                        Spacer(Modifier.height(PcTokens.SpaceS))
                        PcInlineFacts(
                            kinds.keys().asSequence().mapNotNull { k ->
                                val item = kinds.optJSONObject(k) ?: return@mapNotNull null
                                val g = item.optDouble("gain", 0.0)
                                val l = item.optDouble("loss", 0.0)
                                val text = when {
                                    l > 0 -> "-${"%.0f".format(l)}"
                                    g > 0 -> "+${"%.0f".format(g)}"
                                    else -> return@mapNotNull null
                                }
                                kindLabelCn(k) to text
                            }.toList()
                        )
                    }
                    if (gain == 0.0 && loss == 0.0) {
                        Text(
                            "今天还没有积分变动。调用模型会消耗积分，签到与做任务会增加。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---------------- 账号健康 ----------------
        //
        // failure_count / cooldown_until / last_used_at 这三个字段一直在
        // accounts 表里，但界面一个都没引用过（审计结果：界面引用 0 处）。
        // 缺了它们，「冷却中」三个字无法区分「刚被限流 10 秒」和
        // 「卡了半小时还没恢复」，也无法发现「某个号从来没被用过」。
        if (app.accountHealth.length() > 0) {
            item { SectionTitle(text = "账号健康") }
            item {
                PcCard {
                    for (i in 0 until app.accountHealth.length()) {
                        val h = app.accountHealth.optJSONObject(i) ?: continue
                        HealthRow(h)
                        if (i < app.accountHealth.length() - 1) {
                            Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        }
                    }
                }
            }
        }

        // ---------------- 账号额度 ----------------
        if (app.accounts.length() > 0) {
            item { SectionTitle(text = "账号额度") }
            items(app.accounts.length()) { index ->
                val item = app.accounts.optJSONObject(index) ?: return@items
                PcCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
                    insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
                ) {
                    Column {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                FormatKit.accountName(item.optString("nickname"), item.optString("uid")),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (item.optBoolean("enabled", true)) "已启用" else "已停用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            "UID ${FormatKit.shortUid(item.optString("uid"))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            if (item.isNull("credits_remaining")) "额度尚未刷新"
                            else "剩余 ${FormatKit.credits(item.optDouble("credits_remaining"))} / " +
                                "总计 ${FormatKit.credits(item.optDouble("credits_total"))} 积分",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (item.isNull("credits_remaining")) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // ---------------- 最近活动 ----------------
        item { SectionTitle(text = "最近活动") }
        if (app.events.isEmpty()) {
            item {
                PcCard {
                    Text(
                        "暂无活动。启动服务、刷新模型或管理账号后，操作结果会显示在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(minOf(5, app.events.size)) { index ->
                PcCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
                    insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceS)
                ) {
                    Text(
                        app.events.getOrNull(index).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 复制到剪贴板（Compose 没有内置 API，走系统服务）。 */
internal fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    manager.setPrimaryClip(android.content.ClipData.newPlainText("WorkBuddy2API", text))
}

/**
 * 一行账号健康信息。
 *
 * 三种状态各自说明「为什么」：
 *   · 停用   —— 用户自己关的
 *   · 冷却中 —— 带剩余时间与连续失败次数（后者解释"为什么它一直在冷却"）
 *   · 正常   —— 带闲置时长（"多久没被选中过"，可用于发现冷门号）
 */
@Composable
private fun HealthRow(h: org.json.JSONObject) {
    val enabled = h.optBoolean("enabled", true)
    val cooldown = h.optDouble("cooldown_left", 0.0)
    val failures = h.optInt("failure_count", 0)
    val idle = h.optDouble("idle_for", -1.0)

    val (statusText, statusColor) = when {
        !enabled -> "已停用" to MaterialTheme.colorScheme.onSurfaceVariant
        cooldown > 0 -> "冷却 ${cooldownText(cooldown)}" to MaterialTheme.colorScheme.error
        else -> "正常" to MaterialTheme.colorScheme.primary
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            h.optString("label"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
    }
    // 第二行只放「补充原因」，没有可说的就不占行
    val detail = buildList {
        if (failures > 0) add("连续失败 $failures 次")
        if (enabled && cooldown <= 0 && idle >= 0) {
            add(if (idle < 60) "刚刚用过" else "${idleText(idle)}前用过")
        }
        if (enabled && cooldown <= 0 && idle < 0) add("尚未使用过")
    }.joinToString(" · ")
    if (detail.isNotBlank()) {
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 冷却剩余时的可读形式。 */
private fun cooldownText(seconds: Double): String = when {
    seconds < 60 -> "${seconds.toInt()} 秒"
    seconds < 3600 -> "${(seconds / 60).toInt()} 分钟"
    else -> "${(seconds / 3600).toInt()} 小时"
}

/** 闲置时长的可读形式。 */
private fun idleText(seconds: Double): String = when {
    seconds < 3600 -> "${(seconds / 60).toInt()} 分钟"
    seconds < 86400 -> "${(seconds / 3600).toInt()} 小时"
    else -> "${(seconds / 86400).toInt()} 天"
}

/** 账本 kind 的中文名。 */
private fun kindLabelCn(kind: String): String = when (kind) {
    "consume" -> "调用消耗"
    "checkin" -> "签到"
    "task" -> "任务"
    "reward" -> "奖励"
    else -> kind
}
