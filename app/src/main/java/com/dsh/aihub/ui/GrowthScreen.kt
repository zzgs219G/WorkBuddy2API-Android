package com.dsh.aihub.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.aihub.GrowthFacade
import com.dsh.aihub.NativeCore
import com.dsh.aihub.m3.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 成长中心页 —— 对应 1.2.7 的「成长任务」界面。
 *
 * 关于列表 key：最初用 `key = { it.code }` 导致
 *   IllegalArgumentException: Key "" was already used
 * 因为上游可能返回重复 task_code 或空 code。改成「索引 + code」组合，
 * 保证唯一且稳定（索引在刷新前后对同一位置的项保持不变，不会引起无谓重排）。
 *
 * 【Miuix → Material 3 移植】：只改 UI 组件与主题 token，
 *   状态管理、回调签名、数据流、协程调度一律未动。
 */
@Composable
fun GrowthScreen(context: Context, accountKey: String?) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var tasks by remember { mutableStateOf<List<GrowthTaskRow>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // 今日各账号收益。空数组表示今天还没收益（或账本里没记录）。
    var gains by remember { mutableStateOf<org.json.JSONArray>(org.json.JSONArray()) }

    fun refresh() {
        val key = accountKey ?: return
        scope.launch {
            // 今日收益独立于任务列表加载：它是跨账号的本地账本聚合，
            // 与当前选中账号的任务数据无关，所以即使任务接口失败也要显示。
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { NativeCore.todaysGains(context) }
                }.onSuccess { gains = it }
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val s = GrowthFacade.summary(context, key)
                    val list = GrowthFacade.list(context, key).mapIndexed { index, item ->
                        GrowthTaskRow(
                            // 组合 key：索引保证唯一，code 保证可读性
                            id = "$index:${item.code}",
                            code = item.code,
                            title = item.title,
                            description = item.description,
                            progress = item.progressText,
                            rewardText = item.rewardText,
                            claimable = item.claimable,
                            claimed = item.claimed,
                            credit = item.credit,
                            energy = item.energy,
                            ratio = if (item.target > 0) {
                                (item.current.toFloat() / item.target).coerceIn(0f, 1f)
                            } else if (item.claimed) 1f else 0f,
                            hasProgress = item.target > 0
                        )
                    }
                    s to list
                }
            }
            result.fold(
                onSuccess = { (s, list) -> summary = s; tasks = list; error = null },
                onFailure = { error = it.message ?: "加载失败" }
            )
        }
    }

    LaunchedEffect(accountKey) {
        logs = emptyList()
        statusText = ""
        refresh()
    }

    if (accountKey == null) {
        PcCard(title = "成长中心") {
            Text(
                "请先在「账号」页登录一个账号。成长任务与每日福利都按账号结算。",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }

    /** 统一的任务执行入口，避免三处重复写协程与错误处理。 */
    fun run(action: String, block: suspend () -> String) {
        if (running) return
        running = true
        statusText = action
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            result.fold(
                onSuccess = { statusText = it },
                onFailure = { statusText = "失败：${it.message ?: it.javaClass.simpleName}" }
            )
            running = false
            refresh()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ---------------- 今日收益（按账号） ----------------
        //
        // 解决的核心问题：任务做完后，用户不知道「到底加了多少分、加在哪个号上」。
        // 改动前只有执行当下的一行文本（GrowthRunner 算完差值就 return），
        // 切走再回来就没了；多账号时更麻烦，得逐个切账号对比余额才知道谁加了分。
        //
        // 这里读的是本地 credit_ledger（GrowthRunner 已在写 kind="task"），
        // 所以不依赖任务接口是否成功 —— 即使上游挂了，历史收益照样看得到。
        item {
            PcCard(title = "今日收益") {
                if (gains.length() == 0) {
                    Text(
                        "今天还没有收益记录。执行下方任务或签到后，这里会按账号列出到账金额。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 按账号合并：同一账号可能有 task / checkin 多条，先求和再展示，
                    // 否则一个号会重复出现两行，看不出是同一个号。
                    val byAccount = linkedMapOf<String, Double>()
                    for (i in 0 until gains.length()) {
                        val g = gains.optJSONObject(i) ?: continue
                        val label = g.optString("label")
                        byAccount[label] = (byAccount[label] ?: 0.0) + g.optDouble("total")
                    }
                    val total = byAccount.values.sum()
                    PcMetricRow(
                        listOf(
                            "今日合计" to "+${"%.1f".format(total)}",
                            "涉及账号" to byAccount.size.toString(),
                        )
                    )
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    byAccount.entries.forEach { (label, amount) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "+${"%.1f".format(amount)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(PcTokens.SpaceS / 2))
                    Text(
                        "明细见「账号」页 → 积分详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------------- 今日可领 ----------------
        item {
            PcCard(title = "今日可领") {
                val s = summary
                when {
                    s == null && error == null -> Text("加载中…", style = MaterialTheme.typography.bodyLarge)
                    s == null -> Text("加载失败：$error", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error)
                    // 国际版同样有成长任务，不能再显示"无成长体系"这种与下方任务列表
                    // 自相矛盾的文案；改为与国内版一致地展示 claimable 清单。
                    else -> {
                        val arr = s.optJSONArray("claimable")
                        if (arr == null || arr.length() == 0) {
                            Text("当前没有可领项，下方列表可执行任务。",
                                style = MaterialTheme.typography.bodyLarge)
                        } else {
                            for (i in 0 until arr.length()) {
                                Text("· ${arr.optString(i)}", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        s.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errs ->
                            Spacer(Modifier.height(PcTokens.SpaceS))
                            for (i in 0 until errs.length()) {
                                Text("⚠ ${errs.optString(i)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 操作 ----------------
        item {
            PcCard(title = "一键操作") {
                Button(
                    onClick = {
                        run("准备中…") {
                            val results = GrowthFacade.runAll(context, accountKey, reporter = { msg: String ->
                                statusText = msg
                            })
                            logs = flattenResults(results)
                            "全部执行完成"
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (running) "执行中…" else "一键做完所有任务") }
                Spacer(Modifier.height(PcTokens.SpaceM))
                // 统一按钮组：等宽、有固定间距、自动折行（此前四个按钮挤成两排且贴在一起）。
                PcActionGrid(
                    actions = listOf(
                        PcAction("刷新", enabled = !running) { refresh() },
                        PcAction("领每日福利", enabled = !running) {
                            run("领取每日福利…") {
                                val r = GrowthFacade.claimDaily(context, accountKey) { statusText = it }
                                "礼包 ${r.optLong("gift")} / 补偿 ${r.optLong("compensation")} / 抽奖 ${r.optInt("draws")} 次"
                            }
                        },
                        PcAction("猫猫", enabled = !running) {
                            run("猫猫…") { GrowthFacade.runBuddy(context, accountKey) { statusText = it }; "猫猫完成" }
                        },
                        PcAction("开学季", enabled = !running) {
                            run("开学季…") {
                                val r = GrowthFacade.runSchool(context, accountKey) { statusText = it }
                                if (r.optBoolean("skipped")) r.optString("message")
                                else "开学季完成（抽奖 ${r.optInt("draws")} 次）"
                            }
                        }
                    )
                )
                if (statusText.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceM))
                    Text(statusText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    // 积分汇总单独高亮置顶：这是用户最关心的结果（"到底加了多少分"），
                    // 埋在几十行执行日志里不容易看到。
                    val creditLine = logs.firstOrNull { it.contains("积分汇总") }
                    if (creditLine != null) {
                        Text(
                            creditLine.substringAfter('：'),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                    }
                    logs.filterNot { it.contains("积分汇总") }.takeLast(14).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ---------------- 任务清单 ----------------
        if (tasks.isNotEmpty()) {
            item { SectionTitle(text = "成长任务（${tasks.size}）") }
            items(tasks.size, key = { tasks[it].id }) { index ->
                GrowthTaskCard(
                    task = tasks[index],
                    onClaim = {
                        run("领取 ${tasks[index].code}…") {
                            // 用结构化结果区分「已领过」「真有积分」「成功但上游没发奖励」。
                            // 旧实现把 credit==0 一律说成"已领取过"，导致
                            // 「领到了但没积分」和「确实领过了」两种情况无法分辨 ——
                            // 用户反馈的"领取任务不提示是否有积分"就是这个原因。
                            GrowthFacade.claimDetailed(context, accountKey, tasks[index].code).describe()
                        }
                    },
                    onRun = {
                        run("${tasks[index].code}…") {
                            GrowthFacade.runOne(context, accountKey, tasks[index].code) { statusText = it }
                                .optString("message", "完成")
                        }
                    }
                )
            }
        }
    }
}

/** 单个任务卡：标题、进度、奖励、动作按钮。 */
@Composable
private fun GrowthTaskCard(task: GrowthTaskRow, onClaim: () -> Unit, onRun: () -> Unit) {
    // 【M3 移植】原 Miuix Card(insideMargin=...) 改为 PcCard：
    //   内边距语义等价（卡片左右 16dp、上下 14dp），并且与全 App 其它卡片同源。
    PcCard(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
    ) {
        Column {
            Text(task.title.ifBlank { task.code }, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                task.code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (task.description.isNotBlank()) {
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Text(
                    task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            PcInfoRow("进度", task.progress)
            PcInfoRow("奖励", task.rewardText)
            // 只有上游确实给了进度指标（target>0）才画进度条；
            // 这些任务大多没有可查询进度，恒显示 0% 反而误导。
            if (task.hasProgress && !task.claimed) {
                Spacer(Modifier.height(PcTokens.SpaceS))
                LinearProgressIndicator(
                    progress = { task.ratio },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            when {
                task.claimed -> Text(
                    "已领取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                task.claimable -> Button(onClick = onClaim, modifier = Modifier.fillMaxWidth()) { Text("领取奖励") }
                // Material3 的 TextButton 没有 `text =` 参数（Miuix 有），
                // 文案作为尾随 lambda 传入。
                else -> TextButton(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("执行任务") }
            }
        }
    }
}

/** 任务行的 UI 模型。 */
private data class GrowthTaskRow(
    val id: String,
    val code: String,
    val title: String,
    val description: String,
    val progress: String,
    val rewardText: String,
    val claimable: Boolean,
    val claimed: Boolean,
    val credit: Long,
    val energy: Long,
    val ratio: Float,
    val hasProgress: Boolean
)

/** 把 runAll 的结果拍成日志行。 */
private fun flattenResults(results: JSONArray): List<String> = buildList {
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val mark = when (item.optString("status")) {
            "done" -> "✓"
            "claimed" -> "★"     // 领奖成功（可能与"执行"分开显示）
            "skipped" -> "·"
            "unmapped" -> "△"    // 上游有该任务，但客户端尚未适配
            "error" -> "✗"
            else -> "?"
        }
        add("$mark ${item.optString("task_code")}：${item.optString("message")}")
    }
}
