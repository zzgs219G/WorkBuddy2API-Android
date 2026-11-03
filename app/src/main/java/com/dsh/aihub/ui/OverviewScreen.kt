package com.dsh.aihub.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.aihub.ApiHostService
import com.dsh.aihub.FormatKit
import com.dsh.aihub.LanAddress

/**
 * 概览页 —— 仪表盘。
 *
 * 【为什么重写】
 *   上一版把每个数字都压成 `bodySmall` 的一行小字：
 *
 *       今日 8 次  ·  Tokens 12K
 *
 *   这句话里"今日请求 8 次"和"Tokens 12K"是这一屏最该被看见的两个数字，
 *   却和「点账号页可看总额」这类说明文字同一个字号、同一行、靠左对齐 ——
 *   用户的原话是「感觉像是个无关紧要的数据」「像是被额外插进来的一样」。
 *   问题不在字号，在**层级**：文字报告用同一套字重把事实排成一列，
 *   仪表盘用字号/字重/底色把事实分成"主数字 / 标签 / 说明"三层。
 *
 *   所以这一版不再逐条罗列，而是按 dashboard 的组织方式：
 *     · 主数字进指标格（24sp + surfaceContainerHighest 底色）
 *     · 每个账号一行，左名称、右数值、下有比例条
 *     · 状态用色点，不只靠文字
 *     · 分区标题带说明（如「剩余总积分 XXX」），而不是零散的小字
 *
 * 【信息顺序的取舍】
 *   第一屏要回答的三个问题，按优先级排：
 *     ① 服务能用吗      → 本地网关（一行状态 + 按钮，运行时才展开地址）
 *     ② 今天用了多少    → 今日用量（请求 / Tokens / 消耗 / 成功率）
 *     ③ 还剩多少、谁在花 → 账号池（每账号一行：剩余 + 今日消耗 + 额度条）
 *   其余（按来源拆分收支、临期提醒、最近活动）都是补充，排在后面。
 *
 * 【数据口径】
 *   · 请求数 / Tokens / 消耗 取自 usage_logs，按本地时区当天 00:00 起算，
 *     与记录页、用量页同源（见 NativeStore.usageTodayByAccount）。
 *   · 「今日收支」的获得/消耗取自 credit_ledger。账本现在也记未归属账号的
 *     消耗（account_key='-' 哨兵），因此它的总消耗与 usage_logs 的差额只来自
 *     「上游没回报 credit」的请求，不再来自「没归属」——后者已单独列出行。
 *   · 「剩余积分」是各账号缓存额度之和（credit_ledger 之外的上游快照）。
 */
@Composable
fun OverviewScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    val activity = LocalContext.current as? android.app.Activity
    // 概览页自己加载用量摘要。
    //
    // refreshAll() 刻意不调 loadUsage()（全表聚合太重，会让启动卡数秒），
    // 只有记录页/用量页进入时才加载。于是概览页读到的 usageSummary 永远是
    // null，一直显示「今日 0 次 / 0 tokens」—— 用户看到的"没反应"就是这个。
    //
    // 这里用 loadUsageIfStale()：它内部有短窗口去重（见 AppState），
    // 不是每次重组都查库，只有距上次加载超过窗口才真正发起。
    LaunchedEffect(app.revision) { app.loadUsageIfStale() }
    // 必须从 AppState 读（它是 mutableStateOf，广播写入后会触发重组）。
    // 直接读 ApiHostService.running 不会建立订阅，按钮状态不会更新。
    val running = app.serviceRunning
    val starting = app.startingUp

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceS, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ---------------- 本地网关 ----------------
        //
        // 状态与启停按钮同一行：状态本来就是一个词，不值得独占一行。
        // 说明文字只在非正常状态才出现 —— 运行正常时「本机与局域网都可调用」
        // 是零信息量的。
        item {
            PcCard(title = "本地网关") {
                val lanEnabled = app.settings.optString("lanAccess", "0") in setOf("1", "true")
                val lanEntry = remember(lanEnabled) { if (lanEnabled) LanAddress.primary() else null }
                val loopbackUrl = "http://127.0.0.1:${ApiHostService.PORT}/v1"

                Row(verticalAlignment = Alignment.CenterVertically) {
                    DashStatusChip(
                        status = when {
                            running -> DashStatus.OK
                            starting -> DashStatus.WARN
                            else -> DashStatus.IDLE
                        },
                        text = when {
                            running -> "运行中"
                            starting -> "启动中…"
                            else -> "已停止"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { app.toggleServer(activity) }) {
                        Text(when { running -> "停止"; starting -> "取消"; else -> "启动" })
                    }
                    TextButton(onClick = { app.refreshAll() }, enabled = !app.busy) {
                        Text(if (app.busy) "刷新中…" else "刷新")
                    }
                }

                when {
                    starting -> DashCaption("正在校验账号并预热模型，通常数秒内完成；若长时间无变化，请检查通知权限。")
                    !running -> DashCaption("启动后提供 OpenAI 兼容 API。")
                }

                // 地址：运行时才显示。两种都列（本机用回环，其它设备用局域网 IP）。
                if (running) {
                    Spacer(Modifier.height(2.dp))
                    PcCopyableRow("本机", loopbackUrl) {
                        copyToClipboard(context, it)
                        app.showMessage("已复制本机地址：$it")
                    }
                    if (lanEntry != null) {
                        PcCopyableRow("局域网", LanAddress.baseUrl(ApiHostService.PORT, lanEntry)) {
                            copyToClipboard(context, it)
                            app.showMessage("已复制局域网地址：$it")
                        }
                    }
                }
            }
        }

        // ---------------- 今日用量 ----------------
        //
        // 四个格子全部来自 usage_logs 的同一口径汇总。
        // 之所以分成两行（每行 2 格）而不是一行 4 格：
        //   手机竖屏下 4 格每格只有约 80dp 宽，24sp 的数字放不下 "12.3k" 以外的值，
        //   而且 4 个数字并排会失去主次。2×2 是密度与可读性的折中。
        item {
            val today = app.usageToday
            val byAccount = app.usageByAccount
            PcCard(title = "今日用量") {
                if (today == null) {
                    DashEmpty("加载中…")
                } else {
                    val requests = today.optInt("requests")
                    val ok = today.optInt("ok")
                    val failed = today.optInt("failed")
                    val tokens = today.optLong("tokens")
                    val credits = today.optDouble("credits")
                    val successRate = if (requests > 0) ok * 100.0 / requests else 0.0
                    // 有多少个账号今天真正在花钱（消耗 > 0）：这是「谁在消耗」的入口数字。
                    val spenders = remember(app.usageByAccount, app.revision) {
                        var n = 0
                        for (i in 0 until byAccount.length()) {
                            if (byAccount.optJSONObject(i)?.optDouble("credits", 0.0) ?: 0.0 > 0) n++
                        }
                        n
                    }
                    DashMetricGrid(
                        listOf(
                            DashMetric(
                                label = "请求",
                                value = "$requests",
                                unit = "次",
                                hint = if (requests == 0) "今天还没有调用"
                                else if (failed > 0) "成功 $ok · 失败 $failed" else "全部成功",
                            ),
                            DashMetric(
                                label = "Tokens",
                                value = FormatKit.tokens(tokens),
                                hint = if (requests > 0) "均 ${FormatKit.tokens(tokens / requests)} / 次" else "—",
                            ),
                            DashMetric(
                                label = "消耗积分",
                                value = "%.0f".format(credits),
                                hint = if (spenders > 0) "$spenders 个账号在花" else "无消耗",
                                valueColor = if (credits > 0) MaterialTheme.colorScheme.error else null,
                            ),
                            DashMetric(
                                label = "成功率",
                                value = if (requests > 0) "%.0f".format(successRate) else "—",
                                unit = if (requests > 0) "%" else null,
                                hint = if (failed > 0) "失败 $failed 次" else "无失败",
                                valueColor = when {
                                    requests == 0 -> null
                                    failed == 0 -> MaterialTheme.colorScheme.primary
                                    successRate < 90 -> MaterialTheme.colorScheme.error
                                    else -> null
                                },
                            ),
                        ),
                        perRow = 2,
                    )
                }
            }
        }

        // ---------------- 账号池 ----------------
        //
        // 这是「消耗只能看到总数、不知道谁花的」这个问题的落点：
        // 每个账号一行，左边剩余额度、右边今日消耗，下面一条额度比例条。
        //
        // 为什么把「额度」和「今日消耗」放同一行而不是两张卡：
        //   它们是同一个账号的两个属性。分开放，用户就得在两个列表里对账号名，
        //   多账号时这件事极其烦人。一行一个账号，横向比较才成立。
        item {
            val summary = app.credits
            val known = summary?.optInt("known") ?: 0
            DashSectionHeader(
                title = "账号池",
                subtitle = buildString {
                    if (known > 0 && summary != null) {
                        append("剩余总积分 ").append(FormatKit.credits(summary.optDouble("remain")))
                        append(" / ").append(FormatKit.credits(summary.optDouble("total")))
                    } else {
                        append("额度尚未刷新")
                    }
                    append(" · ").append(app.accounts.length()).append(" 个账号")
                },
            )
        }

        if (app.accounts.length() == 0) {
            item { PcCard { DashEmpty("尚未连接账号。到「账号」页登录或导入认证文件。") } }
        } else {
            item {
                PcCard {
                    // uid → 今日消耗，用于在一行里同时给出"还剩多少、今天花了多少"。
                    // 用 uid 而不是 account_key 匹配：usage_logs 只存了 uid。
                    val spendByUid = remember(app.usageByAccount, app.revision) {
                        val map = HashMap<String, Double>()
                        val src = app.usageByAccount
                        for (i in 0 until src.length()) {
                            val row = src.optJSONObject(i) ?: continue
                            val uid = row.optString("account_uid")
                            if (uid.isNotBlank()) map[uid] = row.optDouble("credits", 0.0)
                        }
                        map
                    }
                    val nowSec = System.currentTimeMillis() / 1000.0

                    for (i in 0 until app.accounts.length()) {
                        val account = app.accounts.optJSONObject(i) ?: continue
                        if (i > 0) PcDivider()

                        val enabled = account.optBoolean("enabled", true)
                        val uid = account.optString("uid")
                        val remark = account.optString("remark").trim()
                        val name = if (remark.isNotEmpty()) remark
                        else account.optString("nickname").ifBlank { FormatKit.shortUid(uid) }

                        val remain = if (account.isNull("credits_remaining")) null
                        else account.optDouble("credits_remaining", 0.0)
                        val total = if (account.isNull("credits_total")) null
                        else account.optDouble("credits_total", 0.0)
                        val expireAt = if (account.isNull("credits_expire_at")) null
                        else account.optDouble("credits_expire_at", 0.0)
                        val days = expireAt?.let { ((it - nowSec) / 86400.0).toInt() }

                        // 冷却剩余：区分「刚被限流 10 秒」和「卡了半小时还没恢复」。
                        val cooldown = if (account.isNull("cooldown_until")) 0.0
                        else (account.optDouble("cooldown_until", 0.0) - nowSec).coerceAtLeast(0.0)

                        val (status, statusText) = when {
                            !enabled -> DashStatus.IDLE to "已停用"
                            cooldown > 0 -> DashStatus.BAD to "冷却 ${minutesText(cooldown)}"
                            remain != null && remain <= 0 -> DashStatus.BAD to "额度耗尽"
                            else -> DashStatus.OK to "正常"
                        }

                        val spent = spendByUid[uid] ?: 0.0
                        DashRankRow(
                            name = name,
                            value = if (remain == null) "未刷新" else FormatKit.credits(remain),
                            detail = buildList {
                                add(statusText)
                                if (spent > 0) add("今日 -${"%.1f".format(spent)}")
                                if (days != null && remain != null && remain > 0) {
                                    add(if (days <= 0) "已到期" else "$days 天后到期")
                                }
                            }.joinToString(" · "),
                            // 比例条只在有 total 时画：没有分母就没有"比例"这回事，
                            // 硬画一根满格条会让人误以为额度还很充足。
                            fraction = if (remain != null && total != null && total > 0) {
                                (remain / total).toFloat()
                            } else null,
                            status = status,
                            valueColor = when {
                                remain == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                remain <= 0 -> MaterialTheme.colorScheme.error
                                days != null && days <= 7 -> MaterialTheme.colorScheme.error
                                else -> null
                            },
                        )
                    }
                }
            }
        }

        // ---------------- 临期提醒 ----------------
        //
        // 逐账号列出并指名道姓：只说「有 1 个账号 400 积分将到期」等于没说 ——
        // 用户根本不知道是哪个号，还得自己翻。
        //
        // 这里不用 remember：LazyListScope 的 content 不是 @Composable 上下文，
        // 拿不到组合。这点计算量（几个账号的减法）远小于一次重组的成本。
        val expiringSoon = run {
            val nowSec = System.currentTimeMillis() / 1000.0
            val rows = ArrayList<Triple<String, Double, Int>>()
            for (i in 0 until app.accounts.length()) {
                val acc = app.accounts.optJSONObject(i) ?: continue
                if (acc.isNull("credits_expire_at")) continue
                val remain = if (acc.isNull("credits_remaining")) 0.0
                else acc.optDouble("credits_remaining", 0.0)
                if (remain <= 0) continue
                val days = (acc.optDouble("credits_expire_at", 0.0) - nowSec) / 86400.0
                if (days in 0.0..7.0) {
                    val remark = acc.optString("remark").trim()
                    val label = if (remark.isNotEmpty()) remark
                    else acc.optString("nickname").ifBlank { FormatKit.shortUid(acc.optString("uid")) }
                    rows.add(Triple(label, remain, days.toInt()))
                }
            }
            rows.sortedBy { it.third }
        }
        if (expiringSoon.isNotEmpty()) {
            item {
                PcCard(title = "临期提醒") {
                    expiringSoon.forEach { (label, remain, days) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                "${"%.0f".format(remain)} 积分 · " +
                                    if (days <= 0) "即将到期" else "剩 $days 天",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    DashCaption("这些账号会被优先使用，以免积分作废")
                }
            }
        }

        // ---------------- 今日收支 ----------------
        //
        // 与上方「今日用量」的区别：用量回答「花了多少」，这里回答「进出的构成」——
        // 签到 / 任务给了多少、调用花了多少。两者数据源不同（账本 vs 日志），
        // 所以必须分开显示，不能合并成一个数字。
        item {
            val flow = app.creditFlow
            PcCard(title = "今日收支") {
                if (flow == null) {
                    DashEmpty("加载中…")
                } else {
                    val gain = flow.optDouble("today_gain", 0.0)
                    val loss = flow.optDouble("today_loss", 0.0)
                    val net = flow.optDouble("net", 0.0)
                    DashMetricGrid(
                        listOf(
                            DashMetric(
                                label = "获得",
                                value = "+${"%.0f".format(gain)}",
                                valueColor = if (gain > 0) MaterialTheme.colorScheme.primary else null,
                            ),
                            DashMetric(
                                label = "消耗",
                                value = "-${"%.0f".format(loss)}",
                                valueColor = if (loss > 0) MaterialTheme.colorScheme.error else null,
                            ),
                        ),
                        perRow = 2,
                    )
                    // 净变化单独一行小字：它不是"主数字"而是上面两个的算术结果，
                    // 放进格子会让人以为它是第三个独立指标。
                    DashCaption(
                        "净变化 " + (if (net >= 0) "+" else "") + "%.1f".format(net),
                        color = if (net < 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 按来源拆开：只给一个「消耗 -82」无法回答"钱花在哪了"。
                    //
                    // 关键修正：此前这里用 `else -> return@mapNotNull null` 把
                    // gain==0 且 loss==0 的类别直接丢掉。后果是「签到成功但上游
                    // 未即时到账」（记 0 的那笔）在界面上完全消失 ——
                    // 用户看到的就是「签到了但没计入」。现在改为显示条数：
                    // 金额为 0 时显示"N 次"，让这个动作在账本里可见。
                    val kinds = flow.optJSONObject("by_kind")
                    if (kinds != null && kinds.length() > 0) {
                        Spacer(Modifier.height(PcTokens.SpaceS))
                        DashGroupLabel("按来源")
                        for (k in kinds.keys()) {
                            val item = kinds.optJSONObject(k) ?: continue
                            val g = item.optDouble("gain", 0.0)
                            val l = item.optDouble("loss", 0.0)
                            val count = item.optInt("count", 0)
                            if (g == 0.0 && l == 0.0 && count == 0) continue
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    kindLabelCn(k),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    when {
                                        l > 0 && g > 0 -> "+%.1f / -%.1f".format(g, l)
                                        l > 0 -> "-%.1f".format(l)
                                        g > 0 -> "+%.1f".format(g)
                                        // 金额为 0 但有记录：说明动作发生了、数额未确认
                                        else -> "$count 次（未计额）"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        l > 0 -> MaterialTheme.colorScheme.error
                                        g > 0 -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    // ---------------- 按账号 ----------------
                    //
                    // 这是「消耗只能看到总数、不知道谁花的」的正面回答。
                    // 数据来自 credit_ledger 的 account_key 聚合（NativeStore
                    // .creditFlowToday 的 by_key），与积分详情弹窗逐账号的数字同源。
                    //
                    // 为什么这里还要再有一份按账号，而上面「账号池」已经带了
                    // "今日 -N"：那份只给消耗（来自 usage_logs），这份给**完整收支**
                    // （签到/任务收益也在这里）。用户问"谁花得多"看上一屏，
                    // 问"哪个号今天只进不出"看这里。
                    val byKey = flow.optJSONObject("by_key")
                    if (byKey != null && byKey.length() > 0) {
                        Spacer(Modifier.height(PcTokens.SpaceM))
                        DashGroupLabel("按账号")
                        val keys = byKey.keys().asSequence().toList()
                        val nameByKey = remember(app.accounts, app.revision) {
                            val map = HashMap<String, String>()
                            for (i in 0 until app.accounts.length()) {
                                val acc = app.accounts.optJSONObject(i) ?: continue
                                val key = acc.optString("account_key", acc.optString("uid"))
                                val remark = acc.optString("remark").trim()
                                map[key] = remark.ifBlank {
                                    acc.optString("nickname").ifBlank {
                                        FormatKit.shortUid(acc.optString("uid"))
                                    }
                                }
                            }
                            map
                        }
                        val maxLoss = keys.mapNotNull { byKey.optJSONObject(it)?.optDouble("loss", 0.0) }
                            .maxOrNull() ?: 0.0
                        for (k in keys) {
                            val item = byKey.optJSONObject(k) ?: continue
                            val g = item.optDouble("gain", 0.0)
                            val l = item.optDouble("loss", 0.0)
                            DashRankRow(
                                name = nameByKey[k] ?: FormatKit.shortUid(k),
                                value = (if (g > 0) "+%.0f" else "").format(g) +
                                    (if (g > 0 && l > 0) " / " else "") +
                                    (if (l > 0) "-%.0f".format(l) else ""),
                                detail = buildList {
                                    if (g > 0) add("获得 %.1f".format(g))
                                    if (l > 0) add("消耗 %.1f".format(l))
                                    val n = item.optInt("count", 0)
                                    if (n > 0) add("$n 笔")
                                }.joinToString(" · "),
                                fraction = if (maxLoss > 0) (l / maxLoss).toFloat() else null,
                            )
                        }
                    }

                    // 未归属消耗必须显式说明。
                    //
                    // 没有它，用户把各账号的消耗逐行相加会得到比"总消耗"小的数，
                    // 然后判断"统计坏了"—— 而实际只是那部分请求没落到任何账号
                    // （被网关拒绝前已完成计费）。与其让用户自己发现这个差，
                    // 不如直接给出差额和原因。
                    val unattributed = flow.optDouble("unattributed_loss", 0.0)
                    if (unattributed > 0) {
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        DashCaption(
                            "另有 %.1f 消耗未归属到任何账号（被网关拒绝的请求也可能已计费），"
                                .format(unattributed) + "因此各账号相加会小于上方总消耗。",
                        )
                    }

                    if (gain == 0.0 && loss == 0.0) {
                        Spacer(Modifier.height(PcTokens.SpaceS))
                        DashCaption("今天还没有积分变动。调用模型会消耗积分，签到与做任务会增加。")
                    }
                }
            }
        }

        // ---------------- 账号健康 ----------------
        //
        // failure_count / cooldown_until / last_used_at 这三个字段一直在
        // accounts 表里，但界面一个都没引用过。缺了它们，「冷却中」三个字
        // 无法区分「刚被限流 10 秒」和「卡了半小时还没恢复」，
        // 也无法发现「某个号从来没被用过」。
        //
        // 只在存在异常账号时才出现：全部正常时这一块是零信息量的，
        // 正常的账号在上面「账号池」里已经带状态点了。
        val abnormal = run {
            (0 until app.accountHealth.length()).mapNotNull { app.accountHealth.optJSONObject(it) }
                .filter { h -> !h.optBoolean("enabled", true) || h.optDouble("cooldown_left", 0.0) > 0 || h.optInt("failure_count", 0) > 0 }
        }
        if (abnormal.isNotEmpty()) {
            item { DashSectionHeader(title = "需要留意", subtitle = "${abnormal.size} 个账号异常") }
            item {
                PcCard {
                    abnormal.forEachIndexed { index, h ->
                        if (index > 0) PcDivider()
                        HealthRow(h)
                    }
                }
            }
        }

        // ---------------- 最近活动 ----------------
        if (app.events.isNotEmpty()) {
            item { DashSectionHeader(title = "最近活动") }
            item {
                PcCard {
                    app.events.take(5).forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
 * 一行账号健康信息（只用于异常账号）。
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

    val (status, statusText) = when {
        !enabled -> DashStatus.IDLE to "已停用"
        cooldown > 0 -> DashStatus.BAD to "冷却 ${minutesText(cooldown)}"
        else -> DashStatus.OK to "正常"
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            h.optString("label"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        DashStatusChip(status = status, text = statusText)
    }
    // 第二行只放「补充原因」，没有可说的就不占行
    val detail = buildList {
        if (failures > 0) add("连续失败 $failures 次")
        if (enabled && cooldown <= 0 && idle >= 0) {
            add(if (idle < 60) "刚刚用过" else "${minutesText(idle)}前用过")
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

/** 秒数 → 可读时长（分钟/小时/天三档，够用且不啰嗦）。 */
private fun minutesText(seconds: Double): String = when {
    seconds < 60 -> "${seconds.toInt()} 秒"
    seconds < 3600 -> "${(seconds / 60).toInt()} 分钟"
    seconds < 86400 -> "${(seconds / 3600).toInt()} 小时"
    else -> "${(seconds / 86400).toInt()} 天"
}

/** 账本 kind 的中文名。 */
private fun kindLabelCn(kind: String): String = when (kind) {
    "consume" -> "调用消耗"
    "checkin" -> "每日签到"
    "task" -> "任务奖励"
    "reward" -> "奖励"
    else -> kind
}
