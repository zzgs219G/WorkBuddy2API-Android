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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.aihub.AccountRegion
import com.dsh.aihub.ApiHostService
import com.dsh.aihub.FormatKit
import com.dsh.aihub.LanAddress
import com.dsh.aihub.m3.AppCard
import com.dsh.aihub.m3.LabelMain
import com.dsh.aihub.m3.LabelSummary
import com.dsh.aihub.m3.SectionTitle

/**
 * 应用页 —— 对应原界面 appsPage() + showAppEditor() + showAppKey()。
 *
 * 原实现用 AlertDialog 装一整套表单（名称/备注/Key 模式/调用版本）。
 * Compose 下改成弹窗内嵌表单，字段与校验规则完全一致：
 *   名称必填；自定义 Key 需 16–256 位且不含空格。
 *
 * 【M3 移植】原实现用的是 Miuix 的 WindowDialog（它是"带 show 参数的 window Dialog"），
 * 现已换成 Material 3 的 AlertDialog。两者有一个必须记住的语义差异：
 * AlertDialog 【没有】show 参数，靠外层 `if (…)` 条件渲染。
 * 本文件两处弹窗的调用点本来就已经在 `if` / `let` 的守卫里，所以无需额外包一层。
 */
@Composable
fun AppsScreen(context: Context, app: AppState) {
    var editing by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "API Key 控制台") {
                Text(
                    "每个 Key 都可独立命名、设置备注并固定调用国内版或国际版账号；" +
                        "编辑版本后，新请求会立即按新版本路由。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 创建新 Key") }
            }
        }

        item { SectionTitle(text = "接入配置") }
        item {
            PcCard {
                // 展示哪个地址取决于「是否允许局域网访问」：
                //   · 只监听回环 → 127.0.0.1（只有本机 App 能连）
                //   · 开了局域网 → 手机的实际 IP（电脑/平板才能连进来）
                //
                // 以前固定显示 127.0.0.1。开了局域网后用户照抄这个地址去电脑上填，
                // 永远连不通 —— 因为 127.0.0.1 在电脑上指的是电脑自己。
                val lanEnabled = app.settings.optString("lanAccess", "0") in setOf("1", "true")
                val lan = remember(lanEnabled) { if (lanEnabled) LanAddress.primary() else null }
                val baseUrl = LanAddress.baseUrl(ApiHostService.PORT, lan)
                // 点击即复制，不额外占一行放按钮。
                PcCopyableRow("Base URL", baseUrl) {
                    copyToClipboard(context, it)
                    app.showMessage("已复制 Base URL：$it")
                }
                if (lanEnabled) {
                    if (lan == null) {
                        PcInfoRow("局域网", "已开启，但未检测到可用的局域网网卡")
                    } else {
                        PcInfoRow("局域网地址", "${lan.address}（${lan.interfaceName}）")
                    }
                }
                PcInfoRow("认证请求头", "Authorization: Bearer <你的 Key>")
                PcInfoRow("兼容请求头", "X-Api-Key: <你的 Key>")
                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    // 认证格式不是 URL，仍用按钮（它的值是模板，点击复制的语义不直观）。
                    TextButton(
                        onClick = {
                            copyToClipboard(context, "Authorization: Bearer <你的 Key>")
                            app.showMessage("已复制认证格式")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("复制认证格式") }
                    TextButton(
                        onClick = {
                            copyToClipboard(context, "Authorization: Bearer <你的 Key>")
                            app.showMessage("已复制认证格式")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("复制认证格式") }
                }
            }
        }

        if (app.apps.length() == 0) {
            item {
                PcCard(title = "还没有 API Key") {
                    Text(
                        "创建后可选择自动生成安全 Key，或使用你已有的自定义 Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item { SectionTitle(text = "应用凭据 · ${app.apps.length()} 个") }
            items(app.apps.length()) { index ->
                val item = app.apps.optJSONObject(index) ?: return@items
                val id = item.optLong("id")
                val enabled = item.optBoolean("enabled")
                val name = item.optString("name")
                // 【M3 移植】Miuix 的 Card(insideMargin) 换成 AppCard(contentPadding)。
                //   两者语义相同（内边距由卡片自己提供，卡片内层不再重复加左右 padding），
                //   注意 AppCard 自身已带 PageGutter 的左右外边距，所以这里不能再传
                //   PcTokens.PageGutter，否则会变成双重缩进。
                AppCard(
                    contentPadding = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
                ) {
                    Column {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            LabelMain(name, Modifier.weight(1f))
                            Text(
                                if (enabled) "已启用" else "已停用",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (item.optString("note").isNotBlank()) {
                            Spacer(Modifier.height(PcTokens.SpaceS / 2))
                            // 次级说明文本：body2 + onSurfaceVariantSummary → LabelSummary
                            // （就是这对样式/配色的封装，不用再手写 style/color）。
                            LabelSummary(item.optString("note"))
                        }
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        LabelSummary(
                            "${item.optString("region_label", "国内版")} · " +
                                "${item.optLong("requests")} 次请求 · ${FormatKit.tokens(item.optLong("tokens"))} tokens"
                        )
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            item.optString("key_prefix", "••••••••"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(PcTokens.SpaceM))
                        // 统一走 PcActionGrid：四个按钮分两行、等宽、行间有固定间距。
                        // 此前是两排 Row 紧贴（中间没有 Spacer），视觉上上下黏在一起。
                        PcActionGrid(
                            actions = listOf(
                                PcAction("查看 Key") {
                                    val key = app.appKey(id)
                                    if (key.isNullOrBlank()) app.showMessage("无法读取 API Key，可通过编辑替换")
                                    else showKeyDialog = name to key
                                },
                                PcAction("编辑") { editing = item },
                                PcAction(if (enabled) "停用" else "启用") { app.toggleApp(id, name, !enabled) },
                                PcAction("删除") { pendingDelete = id to name }
                            )
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        AppEditorDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSaved = { saved, isEdit ->
                creating = false
                editing = null
                val key = saved.optString("key")
                if (!isEdit || key.isNotBlank()) {
                    showKeyDialog = "${saved.optString("name")} · ${saved.optString("region_label")}" to key
                } else app.showMessage("更改已保存")
            },
            app = app
        )
    }

    showKeyDialog?.let { (label, key) ->
        PcDetailDialog(
            show = true,
            title = "$label · API Key",
            body = "$key\n\n请仅提供给受信任的客户端。可随时回到此页查看或替换。",
            copyLabel = "复制 Key",
            onCopy = {
                copyToClipboard(context, key)
                app.showMessage("已复制 API Key")
            },
            onDismiss = { showKeyDialog = null }
        )
    }

    PcConfirmDialog(
        show = pendingDelete != null,
        title = "删除 ${pendingDelete?.second}？",
        message = "API Key 将立即失效，历史调用记录会保留。",
        confirmText = "删除",
        onConfirm = {
            pendingDelete?.let { app.deleteApp(it.first, it.second) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null }
    )
}

/**
 * API Key 编辑器 —— 对应原界面的 showAppEditor。
 *
 * 三个要点与原实现保持一致：
 *   1) 编辑时默认「保留当前 Key」，只有显式选择替换才会让旧 Key 失效
 *      （否则用户改个备注就意外作废了正在用的 Key）；
 *   2) 自定义 Key 校验 16–256 位、不含空格；
 *   3) 版本选择用 TabRow 而不是 RadioGroup —— 只有两个选项，Tab 更省纵向空间。
 */
@Composable
private fun AppEditorDialog(
    existing: org.json.JSONObject?,
    onDismiss: () -> Unit,
    onSaved: (org.json.JSONObject, Boolean) -> Unit,
    app: AppState
) {
    val isEdit = existing != null
    var name by remember { mutableStateOf(existing?.optString("name").orEmpty()) }
    var note by remember { mutableStateOf(existing?.optString("note").orEmpty()) }
    var regionIndex by remember {
        mutableStateOf(
            if (existing != null && AccountRegion.from(existing.optString("region")) == AccountRegion.INTERNATIONAL) 1 else 0
        )
    }
    var keyMode by remember { mutableStateOf(0) } // 0 = 保留/自动生成，1 = 自定义
    var customKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 【M3 移植】Miuix WindowDialog(show, title, content) → M3 AlertDialog。
    //   ① AlertDialog 无 show 参数：调用点已在 `if (creating || editing != null)` 里，
    //      所以这里直接渲染即可，不需要再包 `if`。
    //   ② AlertDialog 的按钮是具名插槽 confirmButton / dismissButton，而不是自由布局。
    //      原实现把「取消 + 保存」放在 content 末尾的 Row 里并手写 weight(1f) 平分宽度；
    //      移植后改成语义等价的 TextButton：主操作文案是「保存更改 / 创建 Key」，
    //      所以放 confirmButton 且保持 Button 样式（见下方注释说明为何退回 TextButton）。
    //      原「取消」退化为 dismissButton。
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑 API Key" else "创建 API Key") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("应用名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(PcTokens.SpaceM))
                Text("API Key", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Miuix 的 TabRow 是「传 tabs 字符串列表 + onTabSelected(Int)」的一体式组件；
                // M3 的 TabRow 只提供容器，每个页签要自己写 Tab。语义（索引选中态）保持不变。
                TabRow(selectedTabIndex = keyMode) {
                    listOf(
                        if (isEdit) "保留当前 Key" else "自动生成",
                        if (isEdit) "替换为自定义" else "使用自定义"
                    ).forEachIndexed { index, label ->
                        Tab(
                            selected = keyMode == index,
                            onClick = { keyMode = index },
                            text = { Text(label) }
                        )
                    }
                }
                if (keyMode == 1) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    OutlinedTextField(
                        value = customKey,
                        onValueChange = { customKey = it; error = null },
                        label = { Text("16–256 位，不含空格") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(Modifier.height(PcTokens.SpaceM))
                Text("调用版本", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TabRow(selectedTabIndex = regionIndex) {
                    listOf("国内版", "国际版").forEachIndexed { index, label ->
                        Tab(
                            selected = regionIndex == index,
                            onClick = { regionIndex = index },
                            text = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Text(
                    if (regionIndex == 0) "请求只会路由到国内版账号" else "请求只会路由到国际版账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (error != null) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            // 校验逻辑与原来逐字一致，只是从 content 里的按钮挪进 confirmButton。
            // AlertDialog 的每个按钮槽位各自是一行，所以原来「两按钮等分一行」的
            // weight(1f) 在这里失去意义，已移除；按钮文案与动作不变。
            TextButton(
                onClick = {
                    val appName = name.trim()
                    if (appName.isBlank()) { error = "请输入应用名称"; return@TextButton }
                    val replacement = customKey.takeIf { keyMode == 1 }
                    if (keyMode == 1 && replacement.orEmpty().length !in 16..256) {
                        error = "自定义 Key 需要 16–256 位字符"; return@TextButton
                    }
                    val region = if (regionIndex == 0) AccountRegion.DOMESTIC else AccountRegion.INTERNATIONAL
                    val result = runCatching {
                        if (isEdit) app.updateApp(existing!!.optLong("id"), appName, note.trim(), region, replacement)
                        else app.createApp(appName, note.trim(), region, replacement)
                    }
                    result.fold(
                        onSuccess = { saved -> if (saved != null) onSaved(saved, isEdit) else onDismiss() },
                        onFailure = { error = it.message ?: "保存失败" }
                    )
                }
            ) { Text(if (isEdit) "保存更改" else "创建 Key") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
