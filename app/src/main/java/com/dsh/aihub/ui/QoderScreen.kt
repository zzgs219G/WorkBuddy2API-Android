package com.dsh.aihub.ui

import com.dsh.aihub.qoder.QoderClient
import com.dsh.aihub.qoder.QoderChat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.aihub.qoder.QoderClaim
import com.dsh.aihub.qoder.QoderVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Qoder 协议自检页。
 *
 * 定位很明确：这是**验证工具**，不是正式功能。等协议确认无误后，
 * 这里会被真正的账号管理界面替代。
 *
 * 放在设置里而不是主流程：它现在只能告诉你「协议通不通」，
 * 还不能帮你用 Qoder 对话。先摆正预期，避免当成可用功能。
 */
@Composable
fun QoderScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pat by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var claiming by remember { mutableStateOf(false) }
    var claimResult by remember { mutableStateOf<String?>(null) }
    var reportText by remember { mutableStateOf<String?>(null) }
    var querying by remember { mutableStateOf(false) }
    var chatModel by remember { mutableStateOf("auto") }
    var chatPrompt by remember { mutableStateOf("用一句话说明你是谁") }
    var chatReply by remember { mutableStateOf<String?>(null) }
    var chatting by remember { mutableStateOf(false) }
    var chatDiag by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PcTokens.SpaceL)
    ) {
        Text("Qoder 协议验证", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(PcTokens.SpaceS))
        Text(
            "填入 Qoder 的个人访问令牌（PAT），点验证即可检查协议是否可用。\n" +
                "密码字段只用于本机校验，不会上传到任何第三方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PcTokens.SpaceM))

        // ---- PAT 获取引导 ----
        PcCard(title = "还没 PAT？三步拿到") {
            GuideStep("1", "注册国内版", "qoder.com.cn（国际版账号不能领每日积分）")
            GuideStep("2", "登录后点头像", "进入 设置 → 个人访问令牌")
            GuideStep("3", "创建并复制", "以 pt- 开头，只显示一次")
            Spacer(Modifier.height(PcTokens.SpaceS))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS),
            ) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://qoder.com.cn/"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("打开官网") }

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://qoder.com.cn/"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("令牌页面") }
            }
        }
        Spacer(Modifier.height(PcTokens.SpaceM))

        // 输入框 + 独立的「粘贴」按钮。
        //
        // 为什么不用 TextField 自带的长按菜单：PAT 很长（80+ 字符），
        // 长按选中再复制在手机上很难操作，而且基础 TextField 的长按菜单
        // 在不同 ROM 上行为不一致。给一个一键粘贴按钮更可靠。
        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it.trim() },   // 顺手去掉首尾空白，避免粘贴带入换行
            label = { Text("Qoder PAT") },
            singleLine = false,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PcTokens.SpaceS))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS),
        ) {
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrBlank()) {
                        pat = text.trim()
                        claimResult = null
                        report = null
                    } else {
                        claimResult = "剪贴板为空"
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("从剪贴板粘贴") }

            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("Qoder PAT", pat)
                    )
                    claimResult = "已复制到剪贴板"
                },
                enabled = pat.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("复制内容") }
        }
        Spacer(Modifier.height(PcTokens.SpaceM))

        Button(
            onClick = {
                running = true
                scope.launch {
                    // finally 复位，原因同 GrowthScreen / AppState.task：
                    // 协程被取消时后续语句不执行，状态会永久卡在「验证中」。
                    try {
                        val r = withContext(Dispatchers.IO) { QoderVerify.run(context, pat) }
                        report = r.lines
                        reportText = r.lines.joinToString("\n")
                        ok = r.ok
                    } finally {
                        running = false
                    }
                }
            },
            enabled = !running && pat.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "验证中…" else "开始验证") }

        Spacer(Modifier.height(PcTokens.SpaceM))
        Button(
            onClick = {
                claiming = true
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { QoderClaim(pat).claimAll() }
                        claimResult = r.message
                    } finally {
                        claiming = false
                    }
                }
            },
            enabled = !claiming && pat.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (claiming) "领取中…" else "领取今日积分") }

        Spacer(Modifier.height(PcTokens.SpaceS))
        OutlinedButton(
            onClick = {
                querying = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { QoderClaim(pat).queryBalance() }
                    claimResult = r.message
                    querying = false
                }
            },
            enabled = !querying && pat.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (querying) "查询中…" else "查询余额") }

        claimResult?.let {
            Spacer(Modifier.height(PcTokens.SpaceS))
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        report?.let { lines ->
            Spacer(Modifier.height(PcTokens.SpaceL))
            PcCard(title = if (ok) "验证通过" else "验证未通过") {
                // SelectionContainer 让文字可以长按选中复制 —— 报错信息
                // 只有用户能复制出来，才有办法排查。
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column {
                        lines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    line.startsWith("✓") -> MaterialTheme.colorScheme.primary
                                    line.startsWith("✗") -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(PcTokens.SpaceS))
                // 报错文字在手机上没法长按选中（Text 默认不可选），
                // 给一个整份复制按钮，用户能直接贴给别人看。
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "AIHub 诊断", (reportText ?: "").ifBlank { lines.joinToString("\n") }
                            )
                        )
                        claimResult = "报告已复制到剪贴板"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("复制完整报告") }
            }
        }

        // ---- 对话测试 ----
        Spacer(Modifier.height(PcTokens.SpaceL))
        PcCard(title = "对话测试") {
            Text(
                "用上面的 PAT 换 jobToken，再走 COSY 签名链路发一条真实对话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PcTokens.SpaceS))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QoderChat.MODELS.forEach { (id, label) ->
                    val sel = id == chatModel
                    if (sel) {
                        Button(onClick = { chatModel = id }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        OutlinedButton(onClick = { chatModel = id }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            OutlinedTextField(
                value = chatPrompt,
                onValueChange = { chatPrompt = it },
                label = { Text("要问什么") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(PcTokens.SpaceS))
            Button(
                onClick = {
                    chatting = true
                    chatReply = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val jt = QoderClient(context).exchangeJobToken(pat)
                                val client = QoderChat(jt)
                                val sb = StringBuilder()
                                val text = try {
                                    client.chat(
                                        chatModel,
                                        listOf(QoderChat.Message("user", chatPrompt)),
                                    ) { d ->
                                        sb.append(d)
                                        // 每次增量都在主线程刷新，让用户看到字在流
                                        scope.launch { chatReply = sb.toString() }
                                    }
                                } finally {
                                    // 无论成功失败都把原始 SSE 带回来
                                    val raw = client.lastRawSse
                                    chatDiag = if (raw.isNullOrBlank()) {
                                        "未收到任何 SSE 事件\n请求体长度 ${client.lastPlainBody?.length ?: 0}"
                                    } else {
                                        "原始 SSE（前 1500 字）：\n${raw.take(1500)}"
                                    }
                                }
                                text
                            }
                        }
                        chatReply = r.fold(
                            onSuccess = { it.ifBlank { "（上游返回空内容）" } },
                            onFailure = { "✗ ${it.message}" },
                        )
                        chatting = false
                    }
                },
                enabled = !chatting && pat.isNotBlank() && chatPrompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (chatting) "生成中…" else "发送") }

            chatReply?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(PcTokens.SpaceS))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 诊断原文。用户反馈「一直转圈无输出」，只看转圈分不清是
            // 没收到事件还是字段名不对，把原始 SSE 摊开才查得下去。
            chatDiag?.takeIf { it.isNotBlank() }?.let { d ->
                Spacer(Modifier.height(PcTokens.SpaceS))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        d,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("AIHub 对话诊断", d)
                        )
                        chatDiag = "$d\n\n（已复制）"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("复制诊断") }
            }
        }
    }
}

/** 引导步骤的一行：序号圆圈 + 标题 + 说明。 */
@Composable
private fun GuideStep(no: String, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            no,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
