package com.dsh.aihub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import android.content.Context
import com.dsh.aihub.m3.AppM3Theme
import com.dsh.aihub.m3.M3Icons
import com.dsh.aihub.UpdateScreen
import com.dsh.aihub.AboutScreen

/**
 * 应用外壳 —— 全 App 唯一的页面容器与导航（Material 3 版）。
 *
 * 相对 Miuix 原版的改动仅在**外观层**，导航语义完全保留：
 *   ① 底部导航从 Miuix NavigationBar 换成自建 Row（M3 的 NavigationBarItem
 *      会把每格撑到屏宽/6、固定 80dp 高，纯文字 Tab 显得太空旷）
 *   ② 顶栏从 SmallTopAppBar 换成 M3 CenterAlignedTopAppBar，返回按钮从文字变成图标
 *   ③ 图标从 MiuixIcons 换成 m3/M3Icons 内联图标集（零依赖、零资源）
 *   ④ 主题挂载点从 AppTheme（Miuix）换成 AppM3Theme
 *
 * 刻意保留的部分（原实现的判断仍然成立）：
 *   - Tab 数量与顺序（概览 / 账号 / 模型 / 应用 / 设置 / 成长）
 *   - 二级页仍不入 Tab，由 [SubPage] 状态切换：层级只有两级，
 *     引入 Navigation 组件的返回栈同步成本大于收益
 *   - 返回键分级策略：二级页 → 所属 Tab → 首页 → 交给系统退出
 *   - 「记录」仍留在设置页的二级入口（6 个 Tab 已够，再多会挤）
 *
 * 为什么不再需要 ProvideNavigationEventDispatcher：
 *   那是 Miuix 的弹层组件在 Compose Dialog 里取不到 NavigationEventDispatcherOwner
 *   才需要的补丁。Material 3 的 AlertDialog / ModalBottomSheet 走自己的窗口
 *   返回处理，不依赖该 owner，所以这个包裹层可以整体去掉。
 */

/**
 * 一级 Tab（底部导航）。
 */
enum class AppTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", M3Icons.Overview),
    ACCOUNTS("账号", M3Icons.Accounts),
    MODELS("模型", M3Icons.Models),
    APPS("应用", M3Icons.Apps),
    SETTINGS("设置", M3Icons.Settings),
    GROWTH("成长", M3Icons.Growth)
}
// 图标用 m3/M3Icons 的 7 个内联 ImageVector，不引入任何 icons 依赖：
//   material-icons-extended 会给 APK 增加约 10MB（上万图标，本应用只需 7 个）；
//   material-icons-core 只含约 20 个图标，且在本项目里仅由 Miuix 传递引入——
//   脱离 Miuix 后该依赖也随之消失。内联 7 个 ImageVector 是零成本的确定解。

/** 二级页面（覆盖在 Tab 之上，带返回按钮）。 */
enum class SubPage { NONE, USAGE, INSPECTOR, STORAGE, KEEPALIVE, PROMPT, RECORDS, SCHEDULE, UPDATE, ABOUT, QODER }

/**
 * 当前显示的页面标识。
 *
 * 用密封类而不是 `"tab:XXX"` / `"sub:YYY"` 字符串：字符串方案需要
 * 拼装 + `valueOf` 回解析，一旦格式有偏差就在运行时抛异常（曾因此闪退），
 * 且编译器无法检查；密封类的 `when` 是穷尽的，漏分支直接编译失败。
 */
private sealed class PageKey {
    data class Tab(val tab: AppTab) : PageKey()
    data class Sub(val page: SubPage) : PageKey()
}

/** 二级页标题映射，避免在 TopAppBar 里写 when 分支。 */
private fun SubPage.title(): String = when (this) {
    SubPage.USAGE -> "用量"
    SubPage.INSPECTOR -> "出网取证"
    SubPage.STORAGE -> "存储占用"
    SubPage.KEEPALIVE -> "后台保活"
    SubPage.PROMPT -> "提示词注入"
    SubPage.RECORDS -> "调用记录"
    SubPage.SCHEDULE -> "定时参数"
    SubPage.UPDATE -> "软件更新"
    SubPage.QODER -> "Qoder 协议验证"
    SubPage.ABOUT -> "关于"
    SubPage.NONE -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(context: Context, app: AppState) {
    var tab by rememberSaveable { mutableStateOf(AppTab.OVERVIEW) }
    var sub by rememberSaveable { mutableStateOf(SubPage.NONE) }

    // 系统返回键（含全面屏手势）分级处理。
    //
    // Compose 页面本身不消费返回事件，不注册就会直接传到 Activity 默认实现（finish），
    // 表现为「在任何页面随手势返回都退出应用」。
    // 用 enabled 开关而不是在一个 handler 里分支：未启用的 handler 不参与返回栈，
    // 系统能正确判定「无处可退」并执行退出。
    BackHandler(enabled = sub != SubPage.NONE) { sub = SubPage.NONE }
    BackHandler(enabled = sub == SubPage.NONE && tab != AppTab.OVERVIEW) { tab = AppTab.OVERVIEW }

    AppM3Theme {
        Scaffold(
            topBar = {
                val title = if (sub == SubPage.NONE) tab.label else sub.title()
                CenterAlignedTopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (sub != SubPage.NONE) {
                            // 用文字「返回」而不是图标。
                            //
                            // 之前用 M3Icons.Back 图标，用户反馈完全看不见（先怀疑纯黑
                            // tint，加了显式 tint 仍无效）。图标路径本身没问题 —— 和其他
                            // 能正常显示的图标结构一致 —— 所以问题很可能在
                            // CenterAlignedTopAppBar 对 navigationIcon 槽位的测量上。
                            //
                            // 文字方案绕开整个图标渲染链路，且不依赖任何 vector drawable，
                            // 视觉上也更明确。
                            TextButton(onClick = { sub = SubPage.NONE }) {
                                Text("返回", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
                    // 不设全局「刷新」按钮：各页面自身已有更贴合语境的刷新入口
                    // （概览的「刷新全部」、模型页的「刷新模型目录」、记录页的「刷新统计」、
                    // 账号页的「刷新全部账号额度」），顶栏再挂全局刷新属冗余。
                )
            },
            // bottomBar 不放在 Scaffold 的 bottomBar 槽位里。
            //
            // 原因：Scaffold 的 bottomBar 一旦出现/消失，content 的 padding 会在
            // 同一帧突变成不同的值，AnimatedContent 内部正在做位移的子项会跟着
            // 跳一下 —— 这正是「切换动画乱跳」的一半来源。
            //
            // 改成放进 content 的 Column 里，顶栏 padding 恒定，只有页面区高度变化，
            // 且底栏自身的显隐用 AnimatedVisibility 过渡。
        ) { padding ->
            // 页面切换加过渡动画。
            //
            // 页面标识用带类型的数据类，不用字符串拼接 + valueOf 回解析。
            //
            // 这里踩过两次坑，都记下来避免重犯：
            //
            // ① 方向判定曾用
            //        val forward = tabIndex >= prevTabIndex
            //        LaunchedEffect(tabIndex) { prevTabIndex = tabIndex }
            //    LaunchedEffect 在组合提交后才执行，而 transitionSpec 在整个动画
            //    期间被反复求值 —— 那时 prevTabIndex 已被改写成当前值，forward
            //    恒为 true，左右切换都朝同一方向滑。
            //    改法：只读 AnimatedContent 自带的 initialState/targetState。
            //
            // ② 曾用 "tab:${tab.name}" 拼字符串，再在 transitionSpec 里
            //    AppTab.valueOf(removePrefix("tab:")) 解析回来。一次 ${'$'} 转义
            //    残留让值变成了字面量 "tab:${tab.name}"，valueOf 在首帧抛
            //    IllegalArgumentException —— 表现为「一进 App 就闪退」，而编译器
            //    完全不报错。字符串往返把编译期检查全丢了。
            //    改法：换成 PageKey 密封类，方向读 ordinal、分发用穷尽 when。
            val pageKey: PageKey = if (sub != SubPage.NONE) PageKey.Sub(sub) else PageKey.Tab(tab)

            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                AnimatedContent(
                    targetState = pageKey,
                    transitionSpec = {
                        // 两页同为 tab 时按枚举序定方向；进出二级页按层级定方向：
                        // 进去算前进（新页从右入），返回算后退（旧页从右出）。
                        //
                        // 先落到局部变量：initialState/targetState 是带自定义 getter
                        // 的属性，直接写 is 判断拿不到智能转换。
                        val from = initialState
                        val to = targetState
                        val forward = when {
                            from is PageKey.Tab && to is PageKey.Tab ->
                                to.tab.ordinal >= from.tab.ordinal
                            from is PageKey.Sub && to is PageKey.Sub -> true
                            else -> to is PageKey.Sub
                        }
                        val dir = if (forward) 1 else -1
                        // 位移取 1/5 屏宽：整屏滑动在动画期间会把内容拉成模糊条带，
                        // 1/5 既有方向感又保持内容可辨。
                        val enter = slideInHorizontally { dir * it / 5 } + fadeIn()
                        val exit = slideOutHorizontally { -dir * it / 5 } + fadeOut()
                        (enter togetherWith exit).apply {
                            targetContentZIndex = if (forward) 1f else -1f
                        }
                    },
                    // 高度用 weight 占满剩余空间，底栏不再由 Scaffold 的 bottomBar
                    // 槽位提供，从而避免底栏显隐时 content padding 同帧突变。
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = "page",
                ) { k ->
                    Box(Modifier.fillMaxSize()) {
                        when (k) {
                            is PageKey.Sub -> when (k.page) {
                                SubPage.USAGE -> UsageScreen(context, app)
                                SubPage.INSPECTOR -> InspectorScreen(context, app)
                                SubPage.STORAGE -> StorageScreen(context, app)
                                SubPage.KEEPALIVE -> KeepAliveScreen(context, app)
                                SubPage.QODER -> QoderScreen()
                                SubPage.PROMPT -> PromptScreen(context)
                                SubPage.RECORDS -> RecordsScreen(context, app)
                                SubPage.SCHEDULE -> ScheduleScreen(context, app)
                                SubPage.UPDATE -> UpdateScreen(context, app)
                                SubPage.ABOUT -> AboutScreen(context) { sub = SubPage.UPDATE }
                                // NONE 不会走到这里（pageKey 在 NONE 时取 Tab 分支），
                                // 但 when 必须穷尽，保底给个空。
                                SubPage.NONE -> Unit
                            }
                            is PageKey.Tab -> when (k.tab) {
                                AppTab.OVERVIEW -> OverviewScreen(context, app) { sub = it }
                                AppTab.ACCOUNTS -> AccountsScreen(context, app)
                                AppTab.MODELS -> ModelsScreen(context, app)
                                AppTab.APPS -> AppsScreen(context, app)
                                AppTab.SETTINGS -> SettingsScreen(context, app) { sub = it }
                                AppTab.GROWTH -> GrowthScreen(context, app.selectedAccount)
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = sub == SubPage.NONE,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    BottomBar(selected = tab, onSelect = { tab = it })
                }
            }
        }
    }
}

/**
 * 底部导航栏。
 *
 * 三处尺寸/反馈上的修正：
 *
 *  1) **补 [navigationBarsPadding]**。本应用 targetSdk = 36，Android 15 起对该档
 *     强制 edge-to-edge，窗口一直画到导航条/手势条底下。原先底栏没有任何 inset
 *     处理，于是整体贴在屏幕最下沿、压在系统手势区上 —— 就是「底栏这么低」。
 *  2) **每项最小高度 48dp**（Material 触摸目标下限）。原先 labelLarge 约 18dp
 *     字高，加上下各 7dp 与外层 6dp，合计约 44dp，还只有文字没有图标，又矮又空。
 *  3) **恢复 ripple**（去掉 indication = null）。原先点下去没有任何视觉反馈，
 *     点起来像没生效。
 */
@Composable
private fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { item ->
                val isSelected = selected == item
                val tint = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        // selectable 而不是 clickable：自带 role = Tab 与 selected
                        // 语义，无障碍服务能正确朗读「已选中」。
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(item) },
                        )
                        .heightIn(min = 48.dp)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null, // 下方文字已表达含义，重复朗读反而吵
                        modifier = Modifier.size(22.dp),
                        tint = tint,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
