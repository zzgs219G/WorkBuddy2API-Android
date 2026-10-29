package com.dsh.aihub.ui

import androidx.compose.animation.AnimatedVisibility
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.aihub.AccountRegion
import com.dsh.aihub.FormatKit
import com.dsh.aihub.NativeCore
import com.dsh.aihub.OAuthWebActivity
import com.dsh.aihub.m3.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号页 —— 对应原界面 accountsPage()。
 *
 * 功能对齐清单（逐项对应原实现，一处未减）：
 *   国内版登录 / 国际版登录 / 切换账号登录 / 导入 auth 文件 / 导出全部账号
 *   单账号：启用停用、优先级、导出、删除
 *   批量：刷新全部额度、为全部国内账号签到
 *   展示：账号池、健康状态、额度、今日签到、签到结果
 */
@Composable
fun AccountsScreen(context: Context, app: AppState) {
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

    // 导出目标由 SAF 选定后再写盘：pendingExport 保存待写内容，避免先写盘再让用户选位置。
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingExportName by remember { mutableStateOf("workbuddy-accounts.json") }
    var showExportResult by remember { mutableStateOf<String?>(null) }
    // 连接账号区块的展开状态。默认折叠：它是低频操作，
    // 常驻展开会把额度汇总和账号操作挤出首屏。
    var showConnect by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    // 积分详情弹窗：按账号单独查看积分来源（签到/奖励/消耗）
    var showCreditDetail by remember { mutableStateOf(false) }
    var creditAccountKey by remember { mutableStateOf("") }
    var creditAccountName by remember { mutableStateOf("") }
    var creditDetail by remember { mutableStateOf<org.json.JSONObject?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(target)?.use { stream ->
                        val max = 1024 * 1024
                        val bytes = stream.readNBytes(max + 1)
                        require(bytes.size <= max) { "认证文件不能超过 1 MB" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                }
            }
            raw.fold(
                onSuccess = { app.importAuth(it) },
                onFailure = { importError = it.message ?: "读取失败" }
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload.isNullOrBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法打开目标文件")
                }
            }
            result.fold(
                onSuccess = { app.showMessage("已导出 $pendingExportName（含登录凭证，请妥善保管）") },
                onFailure = { showExportResult = "导出失败：${it.message ?: "写入被拒绝"}" }
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ---------------- 额度汇总 ----------------
        //
        // 置顶理由：进账号页第一件事是看「还剩多少额度」，而不是先划账号列表。
        // 原来这两块排在账号池之后，账号一多就要滚很久才看得到。
        //
        // 顺序：额度汇总 → 连接账号（折叠）→ 批量操作 → 账号池 → 签到结果
        //
        // 前三块占地：额度约 80dp（含进度条）、连接账号折叠后一行、
        // 批量操作约 130dp。首屏基本能看全「还剩多少 + 怎么加账号 + 能做什么」。
        //
        // 连接账号为什么折叠：它有 5 个按钮，常驻展开约 260dp，
        // 会把额度汇总和批量操作挤下去 —— 而它只在「加账号」时用。
        val summary = app.credits
        if (summary != null && summary.optInt("known") > 0) {
            item { SectionTitle(text = "额度汇总") }
            item {
                PcCard {
                    Text(
                        "${FormatKit.credits(summary.optDouble("remain"))} / ${FormatKit.credits(summary.optDouble("total"))} 积分",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(PcTokens.SpaceS / 2))
                    Text(
                        "已汇总 ${summary.optInt("known")} / ${summary.optInt("accounts")} 个账号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val total = summary.optDouble("total")
                    val ratio = if (total > 0) (summary.optDouble("remain") / total).toFloat().coerceIn(0f, 1f) else 0f
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                }
            }
        }


        // ---------------- 连接账号（折叠式） ----------------
        //
        // 默认折叠：这一块有 5 个按钮，常驻会把额度汇总和账号操作挤下去，
        // 而它只是「加账号」时才用。折叠后只占一行，需要时点开。
        item {
            PcCard {
                PcRow(
                    title = "连接账号",
                    summary = if (showConnect) {
                        "国内版 / 国际版 / 导入导出"
                    } else {
                        "国内版登录、国际版登录、导入导出"
                    },
                    onClick = { showConnect = !showConnect },
                )
                // 用 AnimatedVisibility 而不是直接 if：
                // 展开/收起有个高度动画，避免内容瞬间跳变。
                AnimatedVisibility(visible = showConnect) {
                    Column {
                        PcActionGrid(
                            actions = listOf(
                                PcAction("国内版登录", primary = true) {
                                    if (activity != null) startOAuth(context, activity, AccountRegion.DOMESTIC, true, app)
                                },
                                PcAction("国际版登录") {
                                    if (activity != null) startOAuth(context, activity, AccountRegion.INTERNATIONAL, true, app)
                                },
                                PcAction("切换账号登录") {
                                    if (activity != null) {
                                        startOAuth(context, activity, AccountRegion.DOMESTIC, true, app)
                                        app.showMessage("已清空登录态并打开登录窗口")
                                    }
                                },
                                PcAction("导入 auth 文件") { importLauncher.launch(arrayOf("application/json", "*/*")) },
                                PcAction("导出全部账号") {
                                    scope.launch {
                                        val payload = withContext(Dispatchers.IO) { app.exportAllAccounts() }
                                        if (payload.isNullOrBlank()) {
                                            showExportResult = "导出失败：没有可导出的认证数据"
                                        } else {
                                            pendingExport = payload
                                            pendingExportName = "workbuddy-accounts-${app.accounts.length()}.json"
                                            exportLauncher.launch(pendingExportName)
                                        }
                                    }
                                }
                            )
                        )
                        Spacer(Modifier.height(PcTokens.SpaceS))
                        Text(
                            "「切换账号登录」会先清空登录态，用于在同一设备上登录第二个账号；" +
                                "导入支持 WorkBuddy / CodeBuddy 的 .info 与 JSON 认证文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // ---------------- 批量操作 ----------------
        item { SectionTitle(text = "账号操作") }
        item {
            PcCard {
                Button(
                    onClick = { app.refreshCredits() },
                    enabled = !app.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (app.busy) "刷新中…" else "刷新全部账号额度") }
                Spacer(Modifier.height(PcTokens.SpaceS))
                TextButton(
                    onClick = { app.checkInAll() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("为全部启用的国内账号签到") }
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "额度会按账号逐个刷新并汇总；批量签到只处理国内账号，国际版额度由上游自动发放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------------- 账号池 ----------------
        if (app.accounts.length() == 0) {
            item {
                PcCard(title = "尚未连接账号") {
                    Text(
                        "在 App 内置窗口完成登录（不跳浏览器），或导入 WorkBuddy / CodeBuddy 的 .info、JSON 文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item { SectionTitle(text = "账号池 · ${app.accounts.length()} 个") }
            items(app.accounts.length()) { index ->
                val account = app.accounts.optJSONObject(index) ?: return@items
                val uid = account.optString("uid")
                val accountKey = account.optString("account_key", uid)
                val nickname = account.optString("nickname", "WorkBuddy 用户")
                AccountCard(
                    account = account,
                    accountKey = accountKey,
                    nickname = nickname,
                    app = app,
                    onExport = {
                        scope.launch {
                            val payload = withContext(Dispatchers.IO) { app.exportAccount(accountKey) }
                            if (payload.isNullOrBlank()) {
                                showExportResult = "该账号没有可导出的认证数据"
                            } else {
                                pendingExport = payload
                                pendingExportName = "workbuddy-${FormatKit.safeFileName(nickname.ifBlank { uid })}.json"
                                exportLauncher.launch(pendingExportName)
                            }
                        }
                    },
                    onShowCredits = {
                        creditAccountKey = accountKey
                        creditAccountName = nickname
                        creditDetail = null           // 先置空，弹窗内显示"读取中"
                        showCreditDetail = true
                        scope.launch {
                            creditDetail = withContext(Dispatchers.IO) { app.loadCreditDetail(accountKey) }
                        }
                    }
                )
            }
        }
        // ---------------- 签到结果 ----------------
        app.checkinResults?.let { results ->
            if (results.length() > 0) {
                item { SectionTitle(text = "最近签到结果") }
                items(results.length()) { index ->
                    val result = results.optJSONObject(index) ?: return@items
                    // 状态文案要区分三种"没签到"的原因，否则用户无法判断
                    // 是"该做的都做了"还是"有问题"：
                    //   · 国际版         → 本来就没有签到这个动作
                    //   · 账号已停用     → 用户自己关的
                    //   · 今日已签到     → 正常，已签过
                    val status = when {
                        !result.optBoolean("checkin_supported", true) && result.optBoolean("skipped") ->
                            if (result.optBoolean("ok")) "国际版不适用" else "国际版刷新失败"
                        result.optBoolean("skipped") -> "已跳过"
                        result.optBoolean("already") -> "今日已签到"
                        result.optBoolean("ok") -> "签到成功"
                        else -> "签到失败"
                    }
                    PcCard(insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)) {
                        Column {
                            Text(
                                FormatKit.accountName(result.optString("nickname"), result.optString("uid")),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(PcTokens.SpaceS / 2))
                            Text(
                                "$status · UID ${FormatKit.shortUid(result.optString("uid"))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (result.optString("message").isNotBlank()) {
                                Text(
                                    result.optString("message"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 本次到账的积分单独一行高亮：这是签到最核心的结果，
                            // 混在说明文案里不容易看见。
                            if (!result.isNull("credits_gained")) {
                                val gained = result.optDouble("credits_gained", 0.0)
                                if (gained != 0.0) {
                                    Text(
                                        "本次 " + if (gained > 0) "+%.1f".format(gained) else "%.1f".format(gained) + " 积分",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (gained > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    PcAlertDialog(
        show = importError != null,
        title = "导入失败",
        message = importError.orEmpty(),
        onDismiss = { importError = null }
    )
    PcAlertDialog(
        show = showExportResult != null,
        title = "导出",
        message = showExportResult.orEmpty(),
        onDismiss = { showExportResult = null }
    )

    // 积分详情弹窗：按账号查看积分来源（签到/奖励）与去向（消耗）
    if (showCreditDetail) {
        CreditDetailDialog(
            accountName = creditAccountName,
            detail = creditDetail,
            onRefresh = {
                creditDetail = null
                scope.launch {
                    creditDetail = withContext(Dispatchers.IO) { app.loadCreditDetail(creditAccountKey) }
                }
            },
            onClearLedger = {
                app.clearCreditLedger(creditAccountKey)
                scope.launch {
                    creditDetail = withContext(Dispatchers.IO) { app.loadCreditDetail(creditAccountKey) }
                }
            },
            onDismiss = { showCreditDetail = false }
        )
    }
}

/** 单个账号卡片：状态、额度、签到、优先级、启停、导出、删除。 */
@Composable
private fun AccountCard(
    account: org.json.JSONObject,
    accountKey: String,
    nickname: String,
    app: AppState,
    onExport: () -> Unit,
    onShowCredits: () -> Unit
) {
    val uid = account.optString("uid")
    val enabled = account.optBoolean("enabled", true)
    val healthy = account.optBoolean("healthy")
    var showPriority by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    // 账号卡片 —— 从「4 个信息行 + 5 个按钮」压到「2 行信息 + 1 行操作」。
    //
    // 改前一张卡约 300dp 高，一屏（约 640dp 可用）连两个账号都放不下，
    // 而卡片里 60% 的空间被 5 个等宽按钮占着 —— 那些操作里只有「积分详情」
    // 是高频的，其余（优先级 / 导出 / 删除）都是低频配置动作。
    //
    // 现在按「一眼要判断什么」组织：
    //   第一行：昵称 + 状态徽标（这个号能不能用）
    //   第二行：额度 + 签到状态 + 地区（还能用多少、今天领了没）
    //   第三行：主操作（积分详情）+ 溢出菜单（其余低频动作）
    // UID 从独立行收进副标题：它只在核对账号时才需要，完整的可在详情里看。
    val isInternational = AccountRegion.from(account.optString("region")) == AccountRegion.INTERNATIONAL
    val checkedIn = isCheckedInToday(account.optString("last_checkin_date"))
    var menuOpen by remember { mutableStateOf(false) }

    PcCard(insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceS)) {
        Column {
            // ── 第一行：昵称 + 状态 ──
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    nickname,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    when {
                        !enabled -> "已停用"
                        healthy -> "● 健康"
                        else -> "冷却中"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        healthy -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            Spacer(Modifier.height(2.dp))
            // ── 第二行：额度 · 签到 · 地区 ──
            // 几个短事实压成一行小字，不再各占一整行。
            //
            // 额度后面带上本次签到增量：用户最直接的疑问是「签到了到底加没加额度」，
            // 光看「今日已签」答不了这个问题。增量来自 checkinResults 里同一账号的
            // credits_gained（NativeCore.checkIn 用签到前后余额快照算出来的真实差额）。
            val gainedToday = remember(app.checkinResults, accountKey) {
                var value: Double? = null
                val results = app.checkinResults
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        if (item.optString("account_key") == accountKey && !item.isNull("credits_gained")) {
                            value = item.optDouble("credits_gained", 0.0)
                            break
                        }
                    }
                }
                value
            }
            Text(
                buildList {
                    val base = if (account.isNull("credits_remaining")) "额度未刷新"
                    else "${FormatKit.credits(account.optDouble("credits_remaining"))} 积分"
                    // 只在确实算出差额且非 0 时附加，避免出现 "(+0.0)" 这种噪音
                    add(
                        if (gainedToday != null && gainedToday != 0.0) {
                            "$base（今日 ${if (gainedToday > 0) "+" else ""}${"%.1f".format(gainedToday)}）"
                        } else base
                    )
                    add(
                        when {
                            isInternational -> "自动发放"
                            checkedIn -> "今日已签"
                            else -> "今日未签"
                        }
                    )
                    add(account.optString("region_label", "国内版"))
                    // 临期提示：账号级 credits_expire_at 是「还有余量的包里最早的那个」。
                    // 7 天内到期的号会被选号逻辑优先使用（NativeStore.selectAccount 的
                    // urgent 分支），把这件事显示出来，用户才知道"为什么总是这个号"。
                    val expireAt = if (account.isNull("credits_expire_at")) null
                    else account.optDouble("credits_expire_at", 0.0)
                    if (expireAt != null) {
                        val days = ((expireAt - System.currentTimeMillis() / 1000.0) / 86400.0).toInt()
                        if (days in 0..7) add("⚠ ${days}天后到期")
                    }
                    // 优先级必须露出来：它直接决定选号顺序（权重 ×(n+1)），
                    // 是"为什么总是这个号在被用"的唯一解释。收进菜单会让人
                    // 找不到原因 —— 之前压缩卡片时我把它藏起来了，是错的。
                    add("优先级 ${account.optInt("priority")}")
                    add("UID ${FormatKit.shortUid(uid)}")
                }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(PcTokens.SpaceS))
            // ── 第三行：主操作 + 溢出菜单 ──
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onShowCredits) { Text("积分详情") }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("更多") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (enabled) "停用账号" else "启用账号") },
                            onClick = { menuOpen = false; app.setAccountEnabled(accountKey, !enabled) }
                        )
                        DropdownMenuItem(
                            text = { Text("设置优先级") },
                            onClick = { menuOpen = false; showPriority = true }
                        )
                        DropdownMenuItem(
                            text = { Text("导出凭证") },
                            onClick = { menuOpen = false; onExport() }
                        )
                        // 「删除账号」刻意不放在这个菜单里。
                        //
                        // 菜单是「停用/优先级/导出」这类可逆或低风险动作的集合，
                        // 删除是不可逆的。混在一起会有两个问题：
                        //   ① 误触：菜单项之间只隔一行，手指滑一下就可能点到
                        //   ② 语义混乱：删号不该看起来和"导出"是同一类操作
                        // 现在它单独放在卡片底部，与上方内容用分隔线隔开。
                    }
                }
            }
            // ── 删除：独立区域 + 分隔线 + 明显的水平间距 ──
            //
            // 三重隔离都是为了防误触：
            //   ① 分隔线把「可逆操作区」和「不可逆操作区」在视觉上切开
            //   ② 上下各留 SpaceS，手指从上方滑下来不会直接落在它身上
            //   ③ 靠左对齐且不用主题色填充（只是文字色为 error），
            //      不做成醒目的实心按钮 —— 危险操作不该是视觉焦点
            Spacer(Modifier.height(PcTokens.SpaceS))
            PcDivider()
            Spacer(Modifier.height(PcTokens.SpaceS / 2))
            TextButton(onClick = { showDelete = true }) {
                Text("删除账号", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showPriority) {
        PriorityDialog(
            initial = account.optInt("priority"),
            accountLabel = "$nickname · ${FormatKit.shortUid(uid)}",
            onDismiss = { showPriority = false },
            onConfirm = { value ->
                app.setAccountPriority(accountKey, value)
                showPriority = false
            }
        )
    }

    PcConfirmDialog(
        show = showDelete,
        title = "删除账号？",
        message = "${account.optString("region_label")} · $uid\n\n删除后该账号的登录凭证将从本机移除，无法恢复。",
        confirmText = "删除",
        onConfirm = { app.deleteAccount(accountKey, nickname); showDelete = false },
        onDismiss = { showDelete = false }
    )
}

/**
 * 优先级设置对话框 —— 对应原界面的 showPriorityDialog。
 *
 * 保留原版的三条调节路径（步进 / 滑块 / 快捷档位），因为优先级是「选号概率」，
 * 用户往往需要反复微调，单一滑块不好精确定位。
 */
@Composable
private fun PriorityDialog(
    initial: Int,
    accountLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by remember { mutableStateOf(initial.coerceIn(0, 20)) }
    PcPriorityDialogContent(
        value = value,
        onValueChange = { value = it },
        accountLabel = accountLabel,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(value) }
    )
}

/** 判断今天是否已签到（本地日期比较，不依赖上游时区）。 */
internal fun isCheckedInToday(lastDate: String): Boolean {
    if (lastDate.isBlank()) return false
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    return lastDate.take(10) == today
}

/**
 * 发起 OAuth 登录（内置窗口 + 轮询）。
 *
 * 与原实现一致：默认清空 WebView 会话，因为 Google / GitHub 只要留有上次的登录
 * Cookie，再点登录会被静默授权到同一账号，用户永远换不到第二个账号。
 * 轮询成功后才关闭窗口并刷新列表。
 */
internal fun startOAuth(
    context: Context,
    activity: Activity,
    region: AccountRegion,
    clearSession: Boolean,
    app: AppState
) {
    app.showMessage("正在创建${region.label}登录会话…")
    Thread {
        val session = runCatching { NativeCore.beginOAuth(region) }.getOrNull()
        activity.runOnUiThread {
            if (session == null) {
                app.showMessage("发起${region.label}登录失败，请重试")
                return@runOnUiThread
            }
            activity.startActivity(
                Intent(activity, OAuthWebActivity::class.java)
                    .putExtra(OAuthWebActivity.EXTRA_AUTH_URL, session.authUrl)
                    .putExtra(OAuthWebActivity.EXTRA_REGION_LABEL, region.label)
                    .putExtra(OAuthWebActivity.EXTRA_CLEAR_SESSION, clearSession)
            )
            app.showMessage("已打开登录窗口，授权成功后自动关闭")
            pollOAuth(activity, session, app, 0)
        }
    }.start()
}

/** 轮询登录结果，最多 150 次 × 2 秒（与原实现一致）。 */
private fun pollOAuth(activity: Activity, session: com.dsh.aihub.OAuthSession, app: AppState, attempts: Int) {
    if (activity.isFinishing) return
    activity.window.decorView.postDelayed({
        if (activity.isFinishing) return@postDelayed
        Thread {
            val result = runCatching { NativeCore.pollOAuth(context = activity, session = session) }
            activity.runOnUiThread {
                result.fold(
                    onSuccess = { account ->
                        if (account != null) {
                            com.dsh.aihub.AccountSync.afterAuthorize(session.region.defaultDomain)
                            OAuthWebActivity.dismissIfOpen()
                            app.showMessage("${account.region.label}登录成功：${account.nickname}")
                            app.loadAccounts()
                        } else if (attempts < 150) {
                            pollOAuth(activity, session, app, attempts + 1)
                        } else {
                            app.showMessage("${session.region.label}登录等待超时，请重试")
                        }
                    },
                    onFailure = { app.showMessage("登录轮询失败：${it.message}") }
                )
            }
        }.start()
    }, 2000)
}
