package com.dsh.aihub.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.aihub.ConnectivityCheck
import com.dsh.aihub.FailureDiagnosis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 连通性自检页。
 *
 * 单元测试只能覆盖纯逻辑（分类、清洗、排序），永远碰不到网络，所以回答不了
 * 「上游现在还认不认我们」。这个页面就是补那一块：**主动发一次最小请求，
 * 用 [FailureDiagnosis] 把结果翻译成人话。**
 *
 * 与 WorkBuddy-Hub 的「对话」页的区别：那边是完整的内置 Agent（多轮上下文、
 * 工具调用与审批、gzip 压缩），几百行。这里刻意只做「发一句话、看能不能通」，
 * 因为真实对话你已经有 Claude Code / Cherry Studio 这类客户端了 ——
 * 在 App 里再造一个聊天框是重复建设，而且维护成本高。
 */
@Composable
fun ConnectivityScreen(context: Context, app: AppState) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ConnectivityCheck.Report?>(null) }
    var running by remember { mutableStateOf(false) }

    fun run(probeUpstream: Boolean) {
        if (running) return
        running = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConnectivityCheck.run(context, probeUpstream) }
            }
            report = result.getOrElse {
                ConnectivityCheck.Report(
                    listOf(
                        ConnectivityCheck.Step(
                            "自检本身",
                            false,
                            "自检执行失败：${it.message ?: it.javaClass.simpleName}",
                        )
                    )
                )
            }
            running = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "这个检查回答什么") {
                Text(
                    "单元测试只能验证分类、清洗这类纯逻辑，碰不到网络，所以回答不了" +
                        "「上游还认不认我们」。这里会真的发一次最小请求（max_tokens=1，一句话），" +
                        "把结果分类告诉你。\n\n" +
                        "按「本地服务 → 账号 → 上游」顺序检查，前一层不通就不再往后走 ——" +
                        "否则服务没启动时还会去请求上游，得到的错误没有诊断价值。\n\n" +
                        "⚠️ 每次「完整检查」都会真实消耗一次额度。不要频繁点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            PcCard(title = "执行") {
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(
                        onClick = { run(true) },
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (running) "检查中…" else "完整检查") }
                    TextButton(
                        onClick = { run(false) },
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) { Text("仅查本地") }
                }
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Text(
                    "「仅查本地」不发网络请求，只确认服务在跑、账号已登录 —— 不消耗额度。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val current = report
        if (current != null) {
            item {
                PcCard(title = if (current.ok) "全部通过" else "发现问题") {
                    current.steps.forEach { step -> CheckStepRow(step) }
                }
            }

            // 失败时把建议单独放大：这才是用户真正需要照着做的东西，
            // 混在上面的步骤列表里会被当成描述文字略过。
            val failure = current.firstFailure
            if (failure != null && failure.action.isNotBlank()) {
                item {
                    PcCard(title = "怎么处理") {
                        Text(
                            failure.action,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (failure.kind != null) {
                            Spacer(Modifier.height(PcTokens.SpaceS / 2))
                            Text(
                                "分类：${kindLabelFor(failure.kind)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一行检查结果。 */
@Composable
private fun CheckStepRow(step: ConnectivityCheck.Step) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            if (step.ok) "✓" else "✗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Column {
            Text(
                step.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 分类的中文短标签。
 *
 * 与 RecordsScreen 里那份是同一套 wire 名 —— 那边是私有的，
 * 这里再写一份而不共享，是因为它属于展示层文案，
 * 两个页面将来可能各自调整措辞。
 */
private fun kindLabelFor(kind: FailureDiagnosis.Kind): String = when (kind) {
    FailureDiagnosis.Kind.HARD_CREDIT -> "额度不足"
    FailureDiagnosis.Kind.SOFT_RATE -> "被限流"
    FailureDiagnosis.Kind.SESSION_DEAD -> "登录失效"
    FailureDiagnosis.Kind.ACCOUNT_RESTRICTED -> "账号被风控"
    FailureDiagnosis.Kind.MODEL_LIMITED -> "模型不可用"
    FailureDiagnosis.Kind.CLIENT -> "请求被上游拒绝"
    FailureDiagnosis.Kind.NOT_FOUND -> "上游接口不存在（可能已改版）"
    FailureDiagnosis.Kind.SERVER -> "上游服务异常"
    FailureDiagnosis.Kind.NETWORK -> "网络不通"
    FailureDiagnosis.Kind.NO_ACCOUNT -> "无可用账号"
    FailureDiagnosis.Kind.UNKNOWN -> "未识别的失败"
}
