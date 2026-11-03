package com.dsh.aihub.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dsh.aihub.FormatKit
import com.dsh.aihub.m3.AppCard
import com.dsh.aihub.m3.SectionTitle

/**
 * 记录页 —— 对应原界面 recordsPage()。
 *
 * 本次补齐的关键信息（原纯 View 版与初版 Compose 版都没显示）：
 *   · 扣费账号：数据库早就存了 account_uid，但界面从未展示，导致多账号下
 *     无法判断"这次请求是哪个号在扣积分"。这里把 uid 映射成「昵称（短UID）」，
 *     并按版本标注，一眼可辨。
 *   · 本次消耗积分 credits：同表已存，此前也没显示。
 *
 * 字段契约（与 NativeStore.usageRecent 的 light 列一致）：
 *   id / ts / model / protocol / account_uid / input_tokens / output_tokens /
 *   total_tokens / latency_ms / status / error / credits / app_name
 */
@Composable
fun RecordsScreen(context: Context, app: AppState) {
    val records = app.usageRecords
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf(0) } // 0=全部 1=成功 2=失败
    // 单条内容弹窗：正文可能很长（单条上限各 32 万字符），故按需加载 + 弹窗内滚动。
    var showDetail by remember { mutableStateOf(false) }
    var detailTitle by remember { mutableStateOf("") }
    var detailBody by remember { mutableStateOf("") }

    // 进入记录页自动拉取最新记录。
    // 此前只在 refreshAll() 与删除操作后加载，导致新产生的调用记录
    // 必须手动点一次刷新才显示；这里改成每次进入页面（含从其它 Tab 切回）都检查一次，
    // 配合 loadUsageIfStale 的短窗口去重，兼顾实时性与无谓查询。
    LaunchedEffect(Unit) { app.loadUsageIfStale() }

    // uid → 「昵称 · 短UID」映射。多账号下这是"谁在扣分"的唯一可读线索。
    val nameByUid = remember(app.accounts, app.revision) {
        buildMap {
            for (i in 0 until app.accounts.length()) {
                val item = app.accounts.optJSONObject(i) ?: continue
                val uid = item.optString("uid")
                if (uid.isNotBlank()) {
                    put(uid, FormatKit.accountName(item.optString("nickname"), uid))
                }
            }
        }
    }

    val filtered = remember(records, statusFilter, app.revision) {
        (0 until records.length()).mapNotNull { records.optJSONObject(it) }.filter {
            when (statusFilter) {
                1 -> it.optString("status", "ok") == "ok"
                2 -> it.optString("status", "ok") != "ok"
                else -> true
            }
        }
    }

    // 汇总当前筛选结果的消耗，便于核对账户扣分是否对得上
    val totalCredits = filtered.sumOf { it.optDouble("credits", 0.0) }
    val totalTokens = filtered.sumOf { it.optLong("total_tokens") }

    // 失败原因分布：把已落库的 `[kind] summary ｜ 建议：action` 按 kind 聚合。
    //
    // 这是「失效了为什么失效」在界面上的落点：此前失败记录只有一行 `status=error`，
    // 用户必须逐条点开看 error 文本才知道到底怎么回事，条数一多就无法归纳。
    // 这里只统计被筛选出来的失败条目，和下方列表口径一致。
    val failureKinds = remember(filtered) {
        filtered.filter { it.optString("status", "ok") != "ok" }
            .mapNotNull { it.optString("error").takeIf { t -> t.isNotBlank() } }
            // 记录格式固定为 "[kind] ..."，取方括号内的 wire 名
            .map { it.substringAfter('[', "").substringBefore(']', "") }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "调用记录") {
                DashMetricGrid(
                    listOf(
                        DashMetric(
                            label = "本次筛选",
                            value = "${filtered.size}",
                            unit = "条",
                            hint = "共 ${app.usageTotal} 条记录",
                        ),
                        DashMetric(
                            label = "消耗积分",
                            value = "%.0f".format(totalCredits),
                            valueColor = if (totalCredits > 0) MaterialTheme.colorScheme.error else null,
                            hint = if (filtered.isNotEmpty()) {
                                " ${(totalCredits / filtered.size).let { "%.2f".format(it) }} / 次"
                            } else "—",
                        ),
                        DashMetric(
                            label = "Tokens",
                            value = FormatKit.tokens(totalTokens),
                            hint = if (filtered.isNotEmpty()) {
                                " ${FormatKit.tokens(totalTokens / filtered.size)} / 次"
                            } else "—",
                        ),
                    ),
                    perRow = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "共 ${app.usageTotal} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (failureKinds.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    // 失败原因按类型聚合成一行，按条数降序。
                    // 只看这一行就能判断「今天到底是额度用完、被限流，还是上游改版了」，
                    // 不必逐条点开 error 文本。
                    Text(
                        "失败原因：" + failureKinds.joinToString(" · ") { (kind, count) ->
                            "${kindLabel(kind)} $count"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                // 【M3 移植】Miuix 的 TabRow 是「tabs 列表 + selectedTabIndex + onTabSelected」，
                //   Material3 的 TabRow 则是内容槽（尾随 lambda 里自己放 Tab）。
                //   语义等价：选中状态与点击回调逐项对应，选中索引仍由 statusFilter 驱动。
                val statusTabs = listOf("全部", "成功", "失败")
                TabRow(selectedTabIndex = statusFilter) {
                    statusTabs.forEachIndexed { index, label ->
                        Tab(
                            selected = statusFilter == index,
                            onClick = { statusFilter = index },
                            text = { Text(label) }
                        )
                    }
                }
                // 刷新是记录页最常用的动作（数据随时间增长），与清空并列成组。
                Spacer(Modifier.height(PcTokens.SpaceS))
                PcActionGrid(
                    actions = listOf(
                        PcAction("刷新", primary = true, enabled = !app.busy) { app.loadUsage() },
                        PcAction("清空全部记录", enabled = records.length() > 0) { showClearConfirm = true }
                    )
                )
            }
        }

        // ---------------- 今日按账号 ----------------
        //
        // 这是「消耗只能看到总数、不知道谁花的」在记录页的落点。
        // 数据来源与概览页同一份（AppState.usageByAccount，SQL 按 account_uid 聚合），
        // 不在这里重新查库 —— 两处口径必然一致。
        //
        // 为什么放在记录页而不是只在概览：
        //   概览是"扫一眼"的地方，一行一账号够用；真正要核对某个号花了多少时，
        //   用户会来记录页。这里给完整维度：请求数、成功率、tokens、消耗。
        if (app.usageByAccount.length() > 0) {
            item { DashSectionHeader(title = "今日按账号", subtitle = "谁在消耗、花了多少") }
            item {
                PcCard {
                    // 用与 SQL 同源的排序规则（NativeStore.sortUsageByAccount），
                    // 避免"库里排好了、界面上又打乱"的两套口径。
                    val rows = com.dsh.aihub.NativeStore.sortUsageByAccount(app.usageByAccount)
                    // 比例条的分母取「消耗最多的那个账号」而不是总和：
                    // 一个号占 90% 消耗时，用总和作分母的条会短到看不出差别。
                    val maxCredits = rows.maxOfOrNull { it.optDouble("credits", 0.0) } ?: 0.0
                    rows.forEachIndexed { index, row ->
                        if (index > 0) PcDivider()
                        val uid = row.optString("account_uid")
                        val credits = row.optDouble("credits", 0.0)
                        val requests = row.optInt("requests")
                        val failed = row.optInt("failed")
                        val name = row.optString("name").ifBlank {
                            if (uid.isBlank()) "未归属" else FormatKit.shortUid(uid)
                        }
                        DashRankRow(
                            name = name,
                            value = "%.1f".format(credits),
                            detail = buildList {
                                add("$requests 次")
                                if (failed > 0) add("失败 $failed")
                                add(FormatKit.tokens(row.optLong("tokens")) + " tok")
                            }.joinToString(" · "),
                            fraction = if (maxCredits > 0) (credits / maxCredits).toFloat() else null,
                            status = when {
                                requests == 0 -> DashStatus.IDLE
                                failed == 0 -> DashStatus.OK
                                failed * 2 > requests -> DashStatus.BAD
                                else -> DashStatus.WARN
                            },
                        )
                    }
                    // 本列表刻意排除了 account_uid 为空的记录（按 `u.account_uid <> ''`），
                    // 否则「未归属」会挤占一行、还会被算进"谁花得最多"的排行。
                    // 但那部分请求可能已产生计费，因此这里把差额显式说出来 ——
                    // 否则用户把各行消耗相加会得到比「今日消耗」小的数，进而认为统计坏了。
                    val today = app.usageToday
                    val unattributed = today?.optInt("unattributed") ?: 0
                    val unattributedCredits = today?.optDouble("unattributed_credits", 0.0) ?: 0.0
                    if (unattributed > 0 || unattributedCredits > 0) {
                        Spacer(Modifier.height(PcTokens.SpaceS))
                        DashCaption(
                            "另有 $unattributed 次请求未归属到账号（被网关拒绝/无可用账号时仍可能计费），" +
                                "消耗 %.1f 积分，已计入今日总消耗但不计入上方排行。".format(unattributedCredits)
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                PcCard {
                    Text(
                        if (records.length() == 0) "暂无调用记录。使用任一应用 Key 调用本地网关后，记录会显示在这里。"
                        else "当前筛选条件下没有记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item { SectionTitle(text = "最近 ${filtered.size} 条") }
            items(filtered.size) { index ->
                val item = filtered[index]
                UsageRecordCard(
                    item = item,
                    accountLabel = nameByUid[item.optString("account_uid")],
                    onOpenDetail = {
                        // 内容按需加载：列表走 light 查询不含正文，点开时再取单条完整记录。
                        detailTitle = "#${item.optLong("id")} · ${item.optString("model", "auto")}"
                        detailBody = "读取中…"
                        showDetail = true
                        scope.launch {
                            detailBody = withContext(Dispatchers.IO) {
                                app.loadUsageDetail(item.optLong("id"))
                            }
                        }
                    },
                    onDelete = {
                        pendingDelete = item.optLong("id") to item.optString("model", "auto")
                    }
                )
            }
        }
    }

    PcConfirmDialog(
        show = pendingDelete != null,
        title = "删除这条记录？",
        message = "#${pendingDelete?.first} · ${pendingDelete?.second}\n\n记录包含完整请求与响应原文，删除后无法恢复。",
        confirmText = "删除",
        onConfirm = {
            pendingDelete?.let { app.deleteUsage(it.first) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null }
    )

    PcConfirmDialog(
        show = showClearConfirm,
        title = "清空全部记录？",
        message = "将永久删除全部 ${app.usageTotal} 条记录（含输入/输出原文），无法恢复。",
        confirmText = "清空",
        onConfirm = { app.clearUsage(app.usageTotal); showClearConfirm = false },
        onDismiss = { showClearConfirm = false }
    )

    // 单条内容弹窗：展示【注入后】的完整输入（可在其中核对 system 提示词是否已生效）。
    if (showDetail) {
        PcDetailDialog(
            show = true,
            title = detailTitle,
            body = detailBody,
            copyLabel = "复制全部",
            onCopy = {
                copyToClipboard(context, detailBody)
                app.showMessage("已复制该条内容")
            },
            onDismiss = { showDetail = false }
        )
    }
}

/** 单条记录卡：状态、模型、扣费账号、应用、Tokens、积分、延迟、错误。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UsageRecordCard(
    item: org.json.JSONObject,
    accountLabel: String?,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val id = item.optLong("id")
    val ok = item.optString("status", "ok") == "ok"
    val uid = item.optString("account_uid")
    val credits = item.optDouble("credits", 0.0)
    val time = remember(id) {
        runCatching {
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date((item.optDouble("ts", 0.0) * 1000).toLong()))
        }.getOrDefault("")
    }

    // 【M3 移植】Miuix 的 Card 换成 m3 包的 AppCard（封装了 ElevatedCard + 12dp 圆角）。
    //   AppCard 与原 PcCard 一样自带页面左右边距，故不再在调用点叠加 PcTokens.PageGutter，
    //   否则会出现「卡片边距 + 调用点边距」的双重缩进（与 PcComponents 里记录的同款踩坑一致）。
    //   insideMargin 参数在 M3 的 Card 中不存在，改为 AppCard 的 contentPadding。
    var menuOpen by remember { mutableStateOf(false) }
    AppCard(
        // 3 行的紧凑卡片用 SpaceS 内边距：SpaceM 会让上下各多出 14dp 空白，
        // 一屏少看两条记录。
        contentPadding = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceS),
        modifier = Modifier.combinedClickable(
            onClick = onOpenDetail,
            onLongClick = { menuOpen = true },
        ),
    ) {
        Box {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("查看内容") },
                    onClick = { menuOpen = false; onOpenDetail() }
                )
                DropdownMenuItem(
                    text = { Text("删除这条", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
        Column {
            // 单条记录从 9 行压到 2 行 + 一行操作。
            //
            // 原版每条：状态/时间/序号、模型、扣费账号、应用、协议、
            // Tokens（还带输入/输出/合计三段）、积分、耗时 = 9 行 ≈ 300dp。
            // 一屏只能看两条，而这个页面的实际用途是「快速扫一眼有没有异常」。
            //
            // 现在按「扫视时真正会看的三件事」组织：
            //   第一行：模型 + 消耗积分（最右，对齐成列便于比较）
            //   第二行：时间 · 账号 · tokens · 耗时（一行小字）
            // 其余（应用、协议、完整 UID、错误详情）都在「查看内容」里。
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态点放在模型名前面：色点比「✗」更省横向空间，
                // 且失败时整行小字已经是 error 色，不需要再多一个符号重复表达。
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(dashStatusColor(if (ok) DashStatus.OK else DashStatus.BAD))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.optString("model", "auto"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // 等宽字体 + 两位小数：一列记录右对齐后，扫一眼就能比出哪次最贵。
                    "%.2f".format(credits),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if (ok) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildList {
                    add(time)
                    add(accountLabel ?: if (uid.isBlank()) "未记录" else FormatKit.shortUid(uid))
                    add("${FormatKit.tokens(item.optLong("total_tokens"))} tok")
                    add("${item.optLong("latency_ms")}ms")
                    if (!ok && item.optString("error").isNotBlank()) {
                        // 失败时把分类前缀拿出来（记录格式 "[kind] ..."），
                        // 完整文案留给详情页。
                        val kind = item.optString("error").substringAfter('[', "").substringBefore(']', "")
                        if (kind.isNotBlank()) add(kindLabel(kind))
                    }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 不再放两个按钮。
            //
            // 每条记录配一组按钮，一屏两条就要多花 100dp，而这两个动作
            // 都有更省空间的入口：
            //   · 查看内容 → 点整张卡片（列表项的主体动作，符合列表直觉）
            //   · 删除     → 长按弹出菜单（低频且不可逆，正好用长按防误触）
            // 与账号卡的「长按/菜单」策略一致。
        }
    }
}

/**
 * 失败类型的中文短标签。
 *
 * 记录里存的是 [FailureDiagnosis.Kind.wire]（英文枚举名），因为它是入库格式、
 * 需要稳定；界面则展示中文，避免用户对着 `hard_credit` 猜意思。
 *
 * 未收录的值原样返回：宁可显示英文，也不要把未知类型硬塞进某个已知分类。
 */
private fun kindLabel(wire: String): String = when (wire) {
    "hard_credit" -> "额度不足"
    "soft_rate" -> "被限流"
    "session_dead" -> "登录失效"
    "account_restricted" -> "账号被风控"
    "model_limited" -> "模型不可用"
    "client" -> "请求被拒"
    "not_found" -> "接口不存在"
    "server" -> "上游异常"
    "network" -> "网络故障"
    "no_account" -> "无可用账号"
    "unknown" -> "未识别"
    else -> wire
}
