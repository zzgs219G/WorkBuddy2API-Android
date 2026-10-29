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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
