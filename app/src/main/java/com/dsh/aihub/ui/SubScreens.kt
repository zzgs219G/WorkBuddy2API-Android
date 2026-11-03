package com.dsh.aihub.ui

import androidx.compose.ui.platform.LocalContext
import com.dsh.aihub.ShizukuKeeper
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dsh.aihub.ApiHostService
import com.dsh.aihub.FormatKit
import com.dsh.aihub.KeepAliveGuide
import com.dsh.aihub.m3.AppRow
import com.dsh.aihub.m3.SectionTitle
import org.json.JSONArray

/**
 * 用量页 —— 对应原界面 usagePage()。
 *
 * 保留原页面的四个分组：总量指标、调用分布（协议/模型/应用）、额度、最近 24 小时趋势。
 * 「调用分布」是原代码注释里特别说明过「早已算出但一直没呈现」的部分，此处补齐。
 */
@Composable
fun UsageScreen(context: Context, app: AppState) {
    val summary = app.usageSummary
    // 进入用量页自动拉取（与记录页同一份数据，去重窗口共享，避免重复查询）。
    LaunchedEffect(Unit) { app.loadUsageIfStale() }
    // 趋势数据在 LazyColumn 之外算好：LazyColumn 的 content lambda 是 @Composable 但不允许
    // 在其中声明局部 val（会被当作 item 之外的语句），且 remember 放在 item 之间会破坏作用域。
    val nonEmpty = remember(app.usageSeries, app.revision) {
        (0 until app.usageSeries.length()).mapNotNull { app.usageSeries.optJSONObject(it) }
            .filter { it.optLong("count") > 0 }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            // 今日数据做成指标格，累计数据降为一行小字。
            // 今日才是这一页的高频关注点；「累计」是背景信息，不该和今日平级。
            PcCard(title = "用量") {
                val todayRequests = summary?.optLong("today_requests") ?: 0
                val todayTokens = summary?.optLong("today_tokens") ?: 0
                val today = app.usageToday
                DashMetricGrid(
                    listOf(
                        DashMetric(
                            label = "今日请求",
                            value = "$todayRequests",
                            unit = "次",
                            hint = today?.let {
                                val failed = it.optInt("failed")
                                if (failed > 0) "失败 $failed" else "全部成功"
                            },
                        ),
                        DashMetric(
                            label = "今日 Tokens",
                            value = FormatKit.tokens(todayTokens),
                            hint = if (todayRequests > 0) "均 ${FormatKit.tokens(todayTokens / todayRequests)} / 次" else "—",
                        ),
                        DashMetric(
                            label = "今日消耗",
                            value = "%.0f".format(today?.optDouble("credits", 0.0) ?: 0.0),
                            valueColor = if ((today?.optDouble("credits", 0.0) ?: 0.0) > 0) {
                                MaterialTheme.colorScheme.error
                            } else null,
                        ),
                        DashMetric(
                            label = "累计请求",
                            value = FormatKit.tokens(summary?.optLong("total_requests") ?: 0),
                            hint = "累计 ${FormatKit.tokens(summary?.optLong("total_tokens") ?: 0)} tok",
                        ),
                    ),
                    perRow = 2,
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                TextButton(onClick = { app.loadUsage() }, enabled = !app.busy) {
                    Text(if (app.busy) "刷新中…" else "刷新统计")
                }
            }
        }

        // ---------------- 今日按账号 ----------------
        // 与记录页同一份数据（AppState.usageByAccount），只是这里更强调"分布"。
        if (app.usageByAccount.length() > 0) {
            item { DashSectionHeader(title = "今日按账号", subtitle = "消耗与请求分布") }
            item {
                PcCard {
                    val rows = com.dsh.aihub.NativeStore.sortUsageByAccount(app.usageByAccount)
                    val maxCredits = rows.maxOfOrNull { it.optDouble("credits", 0.0) } ?: 0.0
                    rows.forEachIndexed { index, row ->
                        if (index > 0) PcDivider()
                        val uid = row.optString("account_uid")
                        DashRankRow(
                            name = row.optString("name").ifBlank {
                                if (uid.isBlank()) "未归属" else FormatKit.shortUid(uid)
                            },
                            value = "%.1f".format(row.optDouble("credits", 0.0)),
                            detail = "${row.optInt("requests")} 次 · " +
                                FormatKit.tokens(row.optLong("tokens")) + " tok",
                            fraction = if (maxCredits > 0) (row.optDouble("credits", 0.0) / maxCredits).toFloat() else null,
                        )
                    }
                }
            }
        }

        // 【M3 移植】原 Miuix 的 SmallTitle 换成 m3 包的 SectionTitle。
        item { SectionTitle(text = "调用分布") }
        item {
            BreakdownCard(summary?.optJSONArray("by_protocol"), "protocol",
                "成功与失败的网关调用都会计入协议分布。")
        }
        item {
            BreakdownCard(summary?.optJSONArray("by_model"), "model",
                "上游未返回模型名时归入「未指定」。")
        }
        item {
            BreakdownCard(summary?.optJSONArray("by_app"), "app",
                "按应用 Key 归因；Key 删除后历史记录仍保留归因。")
        }

        item { SectionTitle(text = "额度") }
        item {
            val credits = app.credits
            PcCard {
                if (credits == null || credits.optInt("known") == 0) {
                    Text("额度数据未加载，点击下方按钮刷新。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Button(onClick = { app.refreshCredits() }, enabled = !app.busy,
                        modifier = Modifier.fillMaxWidth()) { Text("刷新全部账号额度") }
                } else {
                    val remain = credits.optDouble("remain")
                    val total = credits.optDouble("total")
                    DashMetricGrid(
                        listOf(
                            DashMetric(
                                label = "剩余积分",
                                value = FormatKit.credits(remain),
                                emphasize = true,
                                hint = "已汇总 ${credits.optInt("known")}/${credits.optInt("accounts")} 个账号",
                            ),
                            DashMetric(
                                label = "总额度",
                                value = FormatKit.credits(total),
                                hint = "已用 ${FormatKit.credits((total - remain).coerceAtLeast(0.0))}",
                            ),
                        ),
                        perRow = 2,
                    )
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    DashBar(fraction = if (total > 0) (remain / total).toFloat() else 0f)
                    Spacer(Modifier.height(PcTokens.SpaceS / 2))
                    DashCaption("剩余占总额 ${if (total > 0) "%.0f".format(remain / total * 100) else "—"}%")
                }
            }
        }

        item { SectionTitle(text = "最近 24 小时") }
        if (nonEmpty.isEmpty()) {
            item {
                PcCard { DashEmpty("暂无调用趋势，成功或失败的调用会按小时聚合。") }
            }
        } else {
            // 按小时的趋势做成排行行：右侧是次数，条形长度是相对峰值。
            // 原先每小时一张卡（一行标签值），12 个点就是 12 张卡 ——
            // 竖向滚半天看不出"哪个时段最忙"，而那正是看趋势的唯一目的。
            item {
                PcCard {
                    val peak = nonEmpty.maxOfOrNull { it.optLong("count") } ?: 0L
                    nonEmpty.takeLast(12).forEach { point ->
                        DashRankRow(
                            name = point.optString("bucket"),
                            value = "${point.optLong("count")} 次",
                            detail = FormatKit.tokens(point.optLong("tokens")) + " tok",
                            fraction = if (peak > 0) (point.optLong("count").toFloat() / peak) else null,
                        )
                    }
                }
            }
        }
    }
}

/** 分组统计卡（协议 / 模型 / 应用三处共用）。积分仅在真的 > 0 时展示，避免挂一排 0。 */
@Composable
private fun BreakdownCard(rows: JSONArray?, key: String, emptyHint: String) {
    PcCard {
        // 用 if/else 而不是提前 return：PcCard 的 content 是 ColumnScope lambda，
        // 在其中 return@PcCard 会让编译器认为后续 @Composable 调用可能不在组合上下文里。
        if (rows == null || rows.length() == 0) {
            DashEmpty("暂无统计数据：$emptyHint")
        }
        // 分布用排行行 + 比例条：横向比较"哪个模型/应用花得最多"是这一段的唯一用途，
        // 而「名称 : 次数 · tokens · 积分」的标签值行需要逐行读数字才能比出来。
        // 条形长度取相对最大消耗，没有消耗数据时退化为按次数。
        var maxMetric = 0.0
        for (i in 0 until (rows?.length() ?: 0)) {
            val item = rows?.optJSONObject(i) ?: continue
            maxMetric = maxOf(maxMetric, item.optDouble("credits", 0.0), item.optLong("count").toDouble())
        }
        for (i in 0 until (rows?.length() ?: 0)) {
            val item = rows?.optJSONObject(i) ?: continue
            if (i > 0) PcDivider()
            // grouped() 的键就是传入的 alias（protocol / model / app）。
            val name = item.optString(key).ifBlank { "（未指定）" }
            val credits = item.optDouble("credits", 0.0)
            val count = item.optLong("count")
            val metric = if (credits > 0) credits else count.toDouble()
            DashRankRow(
                name = name,
                value = "${"%.2f".format(credits)} 积分",
                detail = "$count 次 · ${FormatKit.tokens(item.optLong("tokens"))} tok",
                fraction = if (maxMetric > 0) (metric / maxMetric).toFloat() else null,
                valueColor = if (credits > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ================================================================ 出网取证

/**
 * 出网取证页 —— 对应原界面 showRequestInspector() / showInspectorDetail()。
 *
 * 用途：与官方 CLI 逐字节比对「实际发给上游的 headers/body」。
 * 因此明细必须等宽字体、且提供「复制全部」——导出文本本来就是要贴到别处 diff 的。
 */
@Composable
fun InspectorScreen(context: Context, app: AppState) {
    var showAll by remember { mutableStateOf(false) }
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    // 取证记录含请求正文，条数多时读文件要几十毫秒 —— 不能放主线程。
    // 原先写在 remember { } 里，会在首帧同步执行，表现为切进这一页时明显卡顿。
    var entries by remember(app.revision) { mutableStateOf(org.json.JSONArray()) }
    LaunchedEffect(app.revision) {
        entries = withContext(Dispatchers.IO) { app.inspectorEntries() }
    }
    // isEnabled 只读 SharedPreferences，很轻，保留同步读取。
    val enabled = remember(app.revision) { app.inspectorEnabled() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "什么是出网取证？") {
                Text(
                    "记录「实际发给上游的最后一份 headers/body」，用于与官方 CLI 的抓包逐字节比对，" +
                        "确认反代在身份上是否与官方一致。\n\n" +
                        "记录的是发送前一刻的数据，中间不再有任何改写；导出文本保持原始紧凑 JSON 格式，" +
                        "可直接贴进 diff 工具。\n\n" +
                        "敏感头（Authorization / X-Api-Key / Cookie）会保形脱敏——保留头名、前后缀与总长度，" +
                        "但隐藏凭据本体，因此不影响「这个头有没有发、格式对不对」的判断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            PcCard(title = "开关") {
                // 【M3 移植】原 Miuix 的 SwitchPreference 换成「标题/说明 + Switch」的自定义行。
                //   契约示例是裸 Row + Column(weight)，但本文件里 Switch 属于卡片内容，
                //   所以复用契约 §4.2 的 SwitchRow 写法（SettingsScreen.kt 里已建立的同款行），
                //   并沿用下方 Row 已有的 PcTokens.SpaceS/2 垂直节奏，避免开关与被禁用行错位。
                SwitchRow(
                    title = "记录出网请求",
                    summary = if (enabled) "已开启 · 当前 ${entries.length()} 条（仅保留最近 20 条）"
                    else "已关闭 · 开启后会留存请求内容（含对话正文），用完请清空",
                    checked = enabled,
                    onChange = { app.setInspectorEnabled(it) }
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    // 【M3 移植】M3 的 TextButton 没有 `text =` 参数（Miuix 有），
                    // 文案改由尾随 lambda 承载。
                    TextButton(
                        onClick = { showAll = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("查看并复制") }
                    TextButton(
                        onClick = { app.clearInspector() },
                        modifier = Modifier.weight(1f)
                    ) { Text("清空记录") }
                }
            }
        }

        if (entries.length() > 0) {
            item { SectionTitle(text = "已留存 ${entries.length()} 条") }
            items(entries.length()) { index ->
                val entry = entries.optJSONObject(index) ?: return@items
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(entry.optLong("ts")))
                val headerCount = entry.optJSONObject("headers")?.length() ?: 0
                // 【M3 移植】原 Miuix 的 ArrowPreference 换成 m3 包的 AppRow + value = "›"。
                AppRow(
                    title = stamp,
                    summary = "$headerCount 个头 · 账号 ${entry.optString("account").take(6)}",
                    onClick = { detailIndex = index }
                )
            }
        }
    }

    PcDetailDialog(
        show = showAll,
        title = "出网取证（${entries.length()} 条）",
        body = remember(app.revision, showAll) { if (showAll) app.inspectorExport() else "" },
        copyLabel = "复制全部",
        onCopy = {
            copyToClipboard(context, app.inspectorExport())
            app.showMessage("已复制全部取证记录")
        },
        onDismiss = { showAll = false }
    )

    detailIndex?.let { index ->
        val entry = entries.optJSONObject(index)
        if (entry != null) {
            val text = buildString {
                append(entry.optString("method")).append(' ').append(entry.optString("url")).append("\n\n")
                entry.optJSONObject("headers")?.let { hs ->
                    hs.keys().forEach { k -> append(k).append(": ").append(hs.optString(k)).append('\n') }
                }
                append('\n').append(entry.optString("body"))
            }
            PcDetailDialog(
                show = true,
                title = "第 ${index + 1} 条",
                body = text,
                copyLabel = "复制此条",
                onCopy = {
                    copyToClipboard(context, text)
                    app.showMessage("已复制该条取证记录")
                },
                onDismiss = { detailIndex = null }
            )
        }
    }
}

// ================================================================ 存储占用

/** 存储占用页 —— 对应原界面 showStorageInfo()。 */
@Composable
fun StorageScreen(context: Context, app: AppState) {
    var detail by remember { mutableStateOf<String?>(null) }
    // 这两项都要扫描目录、统计文件大小，是最重的查询之一 —— 必须异步。
    // 原先是 remember { } 里同步调用，切进存储页会掉帧（尤其在 WebView 缓存较大时）。
    var info by remember(app.revision) { mutableStateOf(org.json.JSONObject()) }
    var breakdown by remember(app.revision) { mutableStateOf(org.json.JSONObject()) }
    LaunchedEffect(app.revision) {
        val loaded = withContext(Dispatchers.IO) { app.storageInfo() to app.storageBreakdown() }
        info = loaded.first
        breakdown = loaded.second
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            // 8 行改成「3 个指标 + 1 行子项」。
            // 原来「内容合计」下面又列「· 输入 / · 输出 / · 思考链」三个子行，
            // 四个数字占了四行 —— 而它们同属「内容占用」这一个概念。
            PcCard(title = "数据库") {
                DashMetricGrid(
                    listOf(
                        DashMetric("文件大小", FormatKit.bytes(info.optLong("db_bytes"))),
                        DashMetric("记录条数", info.optLong("rows").toString()),
                        DashMetric("可回收", FormatKit.bytes(info.optLong("free_bytes"))),
                    ),
                    perRow = 3,
                )
                val content = info.optLong("input_bytes") + info.optLong("output_bytes") + info.optLong("reasoning_bytes")
                if (content > 0) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    DashFacts(
                        listOf(
                            "内容合计" to FormatKit.bytes(content),
                            "输入" to FormatKit.bytes(info.optLong("input_bytes")),
                            "输出" to FormatKit.bytes(info.optLong("output_bytes")),
                            "思考链" to FormatKit.bytes(info.optLong("reasoning_bytes")),
                        )
                    )
                }
            }
        }

        item {
            // 7 行改成「1 个总数指标 + 2 行分组子项」。
            // 子项按「可清理 / 不可清理」分组比平铺更有用 ——
            // 用户来这里的目的通常是"能腾出多少空间"。
            PcCard(title = "目录占用（实测）") {
                DashMetricGrid(
                    listOf(
                        DashMetric("数据目录合计", FormatKit.bytes(breakdown.optLong("data_total")), emphasize = true),
                    ),
                    perRow = 1,
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                // 子目录用排行行：这一页的用途是"哪儿占空间、能腾多少"，
                // 排行 + 比例条比把四个字节数拼成一句话直观得多。
                val dirs = listOf(
                    "数据库" to breakdown.optLong("databases"),
                    "files" to breakdown.optLong("files"),
                    "WebView 缓存" to breakdown.optLong("webview"),
                    "cache" to breakdown.optLong("cache"),
                    "code_cache" to breakdown.optLong("code_cache"),
                    "外部缓存" to breakdown.optLong("external_cache"),
                ).filter { it.second > 0 }.sortedByDescending { it.second }
                val peak = dirs.firstOrNull()?.second ?: 0L
                dirs.forEachIndexed { index, (label, size) ->
                    if (index > 0) PcDivider()
                    DashRankRow(
                        name = label,
                        value = FormatKit.bytes(size),
                        fraction = if (peak > 0) (size.toFloat() / peak) else null,
                    )
                }
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                DashCaption("以上合计 " + FormatKit.bytes(dirs.sumOf { it.second }))
            }
        }

        item {
            PcCard(title = "清理") {
                Text(
                    "使用记录会保存每次请求的输入/输出/思考链，是数据体积的主要来源；" +
                        "WebView 缓存没有容量上限，登录页的静态资源会长期堆积。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                PcActionGrid(
                    actions = listOf(
                        PcAction("清理 WebView 缓存") { app.clearWebViewCache() },
                        PcAction("截断超长记录内容") { app.trimUsage() },
                        PcAction("清理过期记录") {
                            val days = app.settings.optString("usage_retention_days", "30").toIntOrNull()?.coerceIn(1, 3650) ?: 30
                            app.cleanupUsage(days)
                        }
                    )
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Button(onClick = { app.loadSettings(); detail = storageReport(app) },
                    modifier = Modifier.fillMaxWidth()) { Text("生成完整报告") }
            }
        }
    }

    detail?.let { text ->
        PcDetailDialog(
            show = true,
            title = "存储占用明细",
            body = text,
            onDismiss = { detail = null }
        )
    }
}

private fun storageReport(app: AppState): String = buildString {
    val info = app.storageInfo()
    append("【数据库】\n")
    append("文件大小：${FormatKit.bytes(info.optLong("db_bytes"))}（${info.optLong("rows")} 条记录）\n")
    append("可回收空闲：${FormatKit.bytes(info.optLong("free_bytes"))}\n")
    val content = info.optLong("input_bytes") + info.optLong("output_bytes") + info.optLong("reasoning_bytes")
    append("内容合计：${FormatKit.bytes(content)}\n")
    append("　输入 ${FormatKit.bytes(info.optLong("input_bytes"))} / ")
    append("输出 ${FormatKit.bytes(info.optLong("output_bytes"))} / ")
    append("思考 ${FormatKit.bytes(info.optLong("reasoning_bytes"))}\n")
    val b = app.storageBreakdown()
    append("\n【目录占用（实测）】\n")
    append("数据目录合计：${FormatKit.bytes(b.optLong("data_total"))}\n")
    append("· 数据库目录：${FormatKit.bytes(b.optLong("databases"))}\n")
    append("· WebView 缓存：${FormatKit.bytes(b.optLong("webview"))}\n")
    append("· cache 目录：${FormatKit.bytes(b.optLong("cache"))}\n")
    append("· files 目录：${FormatKit.bytes(b.optLong("files"))}\n")
    append("· 代码缓存：${FormatKit.bytes(b.optLong("code_cache"))}\n")
    append("· 外部缓存：${FormatKit.bytes(b.optLong("external_cache"))}\n")
}

// ================================================================ 后台保活

/** 保活引导页 —— 对应原界面 settingsPage() 里的保活步骤与状态检查。 */
@Composable
fun KeepAliveScreen(context: Context, app: AppState) {
    var report by remember { mutableStateOf<String?>(null) }
    val steps = remember { KeepAliveGuide.steps() }

    // 授权状态会随用户去系统设置操作而变化，而 Composition 在此期间并不重组。
    // 用一个自增令牌在每次回到前台时强制重算，否则用户授权完返回本页，
    // 界面仍显示"未授权"，会误以为没生效而反复操作。
    var refreshToken by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 关键三项授权状态：电池优化白名单、精确闹钟、安装未知应用。
    // 其中电池优化白名单是后台存活的决定性一环——没有它，Doze 会冻结服务，
    // 其余保活手段（看门狗、悬浮窗、START_STICKY）全部失效。
    val ignoringBattery = remember(refreshToken) {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }
    val exactAlarmOk = remember(refreshToken) {
        if (android.os.Build.VERSION.SDK_INT < 31) true
        else runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                .canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    val notificationsGranted = remember(app.revision, refreshToken) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }
    val channelDisabled = remember(app.revision, refreshToken) { ApiHostService.isChannelDisabled(context) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "为什么还会被杀？") {
                Text(
                    "${KeepAliveGuide.vendorLabel()} 对后台应用有独立于「电池优化白名单」的一套管控，" +
                        "必须逐项手动放开。上面的白名单只解决其中一项，其余开关若不打开，" +
                        "本地 API 在锁屏或长时间待机后仍会被系统回收。\n\n" +
                        "另外要说明：被回收本身很难完全避免，真正影响体验的是「回收后能否自动恢复」。" +
                        "本版已加入开机自启、划掉任务后自动重启、15 分钟看门狗兜底，即使中途被杀也会自动拉回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }


        // ---- Shizuku：一次性自动放行 ----
        //
        // 放在「请依次完成」之前：如果用户能用 Shizuku，下面那一长串手动步骤
        // 大部分可以跳过。先给自动化方案，再给手动兜底，顺序符合「最省事的先试」。
        item {
            val shizuku = remember(refreshToken) { ShizukuKeeper.status() }
            val activity = LocalContext.current as? android.app.Activity
            PcCard(title = "用 Shizuku 一键放行") {
                PcRow(
                    title = when {
                        !shizuku.running -> "Shizuku 未运行"
                        !shizuku.granted -> "点此授权"
                        else -> "已就绪"
                    },
                    summary = when {
                        !shizuku.running -> "需先安装并启动 Shizuku（无需 root，用 ADB 授权即可）"
                        !shizuku.granted -> "授权后可自动完成下面的所有系统设置"
                        else -> "可再次点击重新应用所有放行项"
                    },
                    value = if (shizuku.granted) "重新应用" else "授权",
                    onClick = {
                        if (activity == null) return@PcRow
                        if (!shizuku.running) {
                            app.showMessage("请先安装并启动 Shizuku")
                            return@PcRow
                        }
                        if (!shizuku.granted) {
                            ShizukuKeeper.requestPermissionAndBind(activity)
                            app.showMessage("请在弹窗中允许 AIHub 使用 Shizuku")
                        } else {
                            val n = ShizukuKeeper.applyKeepAlive(context)
                            app.showMessage(
                                if (n >= 0) "已应用 $n 项系统放行"
                                else "服务连接中，稍后再试"
                            )
                        }
                    },
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "Shizuku 通过 ADB 授权执行系统命令，**不需要 root**。授权一次后，" +
                        "可自动完成后台限制、唤醒锁、精确闹钟、前台服务、电池白名单等放行项 —— " +
                        "也就是下面那些需要你手动点开的地方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle(text = "请依次完成以下设置") }
        item {
            PcCard {
                steps.forEachIndexed { index, step ->
                    // 【M3 移植】原 Miuix 的 ArrowPreference 换成 m3 包的 AppRow + value = "›"。
                    AppRow(
                        title = "${index + 1}. ${step.title}",
                        summary = step.desc,
                        onClick = {
                            KeepAliveGuide.open(context, step.key)
                            if (step.key == "lock") app.showMessage("本项无法自动跳转：请打开最近任务，下拉本应用卡片后点锁图标")
                        }
                    )
                }
            }
        }

        // 关键授权：这是后台能否存活的先决条件。
        // 单独成卡并置顶，因为实测未授权时其余保活手段（看门狗、悬浮窗、
        // START_STICKY）全部失效——Doze 直接把进程冻结。原实现里
        // KeepAliveGuide.requestIgnoreBattery() 已写好但从未被界面调用，
        // 用户根本无从授权，这是"后台仍然不行"的直接原因。
        item {
            PcCard(title = "关键授权（缺一不可）") {
                AuthRow(
                    label = "电池优化白名单",
                    ok = ignoringBattery,
                    detail = if (ignoringBattery) "已加入，系统不再冻结本服务"
                    else "未加入。Doze 会冻结服务，其余保活手段全部失效",
                    action = if (ignoringBattery) null else "去授权"
                ) { KeepAliveGuide.requestIgnoreBattery(context) }

                AuthRow(
                    label = "精确闹钟",
                    ok = exactAlarmOk,
                    detail = if (exactAlarmOk) "已允许，看门狗可准时唤醒"
                    else "未允许。服务被杀后可能数小时才恢复",
                    action = if (exactAlarmOk) null else "去授权"
                ) { KeepAliveGuide.requestExactAlarm(context) }
            }
        }

        item {
            PcCard(title = "状态检查") {
                Button(
                    onClick = {
                        val (lines, manual) = KeepAliveGuide.statusCheck(context, notificationsGranted, channelDisabled)
                        report = buildString {
                            lines.forEach { append(it).append('\n') }
                            append("\n系统未开放查询接口，请自行确认：\n")
                            manual.forEach { append("· ${it.title}\n") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("一键检查当前状态") }
                Spacer(Modifier.height(PcTokens.SpaceS))
                // 【M3 移植】M3 的 TextButton 没有 `text =` 参数（Miuix 有），
                // 文案改由尾随 lambda 承载。
                TextButton(
                    onClick = { KeepAliveGuide.requestExactAlarm(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开启精确闹钟（看门狗命门）") }
                if (channelDisabled) {
                    TextButton(
                        onClick = { KeepAliveGuide.openChannelSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重开前台服务通知渠道") }
                }
            }
        }
    }

    report?.let { text ->
        PcDetailDialog(
            show = true,
            title = "保活状态检查",
            body = text,
            mono = false,
            onDismiss = { report = null }
        )
    }
}

/**
 * 轻量开关行：标题 + 说明 + Switch。
 *
 * 【M3 移植】替代 Miuix 的 SwitchPreference。不经过 Preference 包装层、也不直接使用
 *   m3 包的 AppRow —— AppRow 的 value 插槽只接 String，塞不下 Switch 这种可组合物；
 *   而 PcRow 的 trailing 插槽是为本文件这种「卡片内行」场景准备的，所以这里用 PcRow。
 *   垂直节奏用 PcTokens.SpaceS / 2，与同行所在的 PcCard 内容保持一致。
 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    PcRow(
        title = title,
        summary = summary,
        trailing = { Switch(checked = checked, onCheckedChange = onChange) }
    )
}

/**
 * 授权状态行：左侧状态文字 + 右侧「去授权」按钮。
 *
 * 为什么不用 Switch：这类授权不在本应用内完成，点了会跳到系统设置页，
 * 用开关样式会让用户以为能在应用内直接切换。
 */
@Composable
private fun AuthRow(
    label: String,
    ok: Boolean,
    detail: String,
    action: String?,
    onAction: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS / 2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (ok) "✓" else "✗",
                style = MaterialTheme.typography.titleMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (action != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error
        )
    }
}
