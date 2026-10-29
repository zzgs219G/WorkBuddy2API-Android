package com.dsh.aihub.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 对话框封装 —— 替代原界面里十几个 `AlertDialog.Builder` 调用。
 *
 * 【Material 3 移植】原先基于 Miuix 的 WindowDialog，现在全部换成 M3 的 AlertDialog。
 *   换用 WindowDialog 的原因（见下）已随 Miuix 一起消失，但结论仍然记在这里备查：
 *
 * 为什么当年用 WindowDialog 而不是 OverlayDialog：
 * OverlayDialog 走 MiuixPopupHost → DialogContentLayout → NavigationBackHandler，
 * 在纯 Compose 且宿主未提供 NavigationEventDispatcherOwner 时会抛
 * "No NavigationEventDispatcher was provided"。
 * WindowDialog 直接基于 Compose 的 window Dialog（它的源码里 0 处 BackHandler），
 * 完全绕开这条依赖链，是零崩溃风险的选项。返回键关闭由系统 Dialog 自身承担。
 * M3 的 AlertDialog 同样直接走 Compose 的 window Dialog、不依赖该 owner，所以这条
 * 「零崩溃风险」的性质被完整继承。
 *
 * 另：OverlayDialog / WindowDialog 都【没有】buttons 参数，按钮要写进 content 里，
 * 这是 Miuix 的设计（content 即整个对话框体）。M3 的 AlertDialog 反过来 ——
 * 按钮走具名的 confirmButton / dismissButton 插槽，所以各封装已按插槽重新分配；
 * 底部按钮行原本手写的 weight(1f) 等分宽度一并去掉：M3 规格是按钮右对齐、
 * 由组件统一负责间距，不需要也不应该由调用方平分。
 *
 * 【调用点无需改动】这里刻意保留了 `show: Boolean` 参数（原签名不动），
 * 内部用 `if (show)` 条件渲染来适配 AlertDialog 没有 show 参数这件事。
 * 如果反过来删掉 show 参数、要求调用点自己加 `if`，就会强制修改 11 处调用点的签名。
 */
@Composable
fun PcConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    /**
     * 危险操作：确认按钮变为「长按 1.5 秒才生效」，且文案提示这一点。
     *
     * 用于删除账号这类不可逆动作。理由：普通确认框的确认与取消两个按钮
     * 相邻、都是同样大小的文字按钮，手指从取消滑到确认几乎无感 ——
     * 之前把删除按钮从卡片上挪进菜单、又加分隔线，解决的是「不要显眼」，
     * 但真正的误触点在这个对话框里。长按是这类动作的通用解
     * （GitHub 删仓库、支付宝删除账户都是这么做的）。
     */
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                // 正文可能很长（保活检查报告等），限高 + 滚动，避免撑出屏幕导致按钮点不到。
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    if (danger) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "确认需要长按「$confirmText」约 1.5 秒 —— 这样误触不会生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                if (danger) LongPressConfirmButton(confirmText, onConfirm)
                else TextButton(onClick = onConfirm) { Text(confirmText) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(dismissText) }
            }
        )
    }
}

/**
 * 长按 1.5 秒才触发的确认按钮，带进度反馈。
 *
 * 为什么需要进度反馈：只要求长按而不给视觉响应时，用户会以为按钮坏了，
 * 反复点或反复长按 —— 反而更容易在某个瞬间凑巧触发。
 * 这里按住时进度条从左到右填满，松手立即归零。
 */
@Composable
private fun LongPressConfirmButton(text: String, onConfirm: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(contentAlignment = Alignment.Center) {
        // 进度条作为背景，宽度随按住时长增长
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        RoundedCornerShape(6.dp)
                    )
            )
        }
        TextButton(
            onClick = { /* 点击不生效：必须长按 */ },
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.animateTo(1f, tween(1500, easing = LinearEasing))
                            // 动画跑完 = 按住够久 → 触发并复位
                            progress.snapTo(0f)
                            onConfirm()
                        }
                        tryAwaitRelease()
                        // 提前松手：取消
                        job.cancel()
                        scope.launch { progress.snapTo(0f) }
                    }
                )
            }
        ) {
            Text(text, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 只有「知道了」的提示框。 */
@Composable
fun PcAlertDialog(
    show: Boolean,
    title: String,
    message: String,
    buttonText: String = "知道了",
    onDismiss: () -> Unit
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(buttonText) }
            }
        )
    }
}

/**
 * 单行输入对话框（自定义 API Key、设置项数值等）。
 *
 * @param value 当前值（外部 state 持有，便于校验后回填错误）
 * @param errorText 非空时在输入框下方红字提示
 */
@Composable
fun PcInputDialog(
    show: Boolean,
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmText: String = "保存",
    errorText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (errorText != null) {
                        Spacer(Modifier.height(PcTokens.SpaceS))
                        Text(
                            errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text(confirmText) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }
}

/**
 * 明细查看对话框：等宽文本 + 一键复制。
 * 用于「查看 API Key」「出网取证明细」「存储占用明细」「保活状态检查」。
 *
 * ⚠️ 超长文本处理（本函数最重要的约束）：
 *   出网取证的 body 是【完整请求体】，一条就可能达到几十到上百 KB。
 *   把这么长的字符串整个交给 Text() 渲染会直接闪退 —— 原因不是长度本身，
 *   而是 Compose 会对整段文本做分词与布局测量，单帧工作量大到触发
 *   「GL 上下文丢失 / 渲染超时」并让进程被杀（表现为"点某条日志就闪退"）。
 *   因此这里在渲染前先做长度上限裁剪，并明确告知用户已截断、可用「复制」拿全文。
 *
 *   注意限制只作用于【显示】：「复制」始终复制完整原文，
 *   因为取证文本的用途就是整段贴进 diff 工具比对。
 */
@Composable
fun PcDetailDialog(
    show: Boolean,
    title: String,
    body: String,
    mono: Boolean = true,
    copyLabel: String? = null,
    onCopy: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    // 渲染上限：按字符数裁剪。取 20000 字符是权衡后的值 ——
    // 足以完整显示绝大多数 Key / 存储报告 / 状态检查，
    // 又远低于会让 Compose 布局测量卡死的量级（实测 100KB+ 必崩）。
    val display = remember(body) {
        if (body.length <= DETAIL_DISPLAY_LIMIT) body
        else body.take(DETAIL_DISPLAY_LIMIT) +
            "\n\n… 已省略 ${body.length - DETAIL_DISPLAY_LIMIT} 字符（共 ${body.length} 字符）\n" +
            "点击下方「${copyLabel ?: "复制"}」可获取完整内容。"
    }
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = display,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (mono) FontFamily.Monospace else null
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
            // 只有存在复制回调时才提供 dismissButton 插槽。
            // 断言依据：M3 的 dismissButton 是【非空】的可选参数（默认 { }），
            // 所以不能写 `dismissButton = if (...) {...} else null` —— 传 null 编译不过。
            dismissButton = if (onCopy != null) {
                { TextButton(onClick = onCopy) { Text(copyLabel ?: "复制") } }
            } else {
                {}
            }
        )
    }
}

/** 明细弹窗的渲染长度上限（字符）。超过则截断显示，但不影响复制全文。 */
private const val DETAIL_DISPLAY_LIMIT = 20_000
