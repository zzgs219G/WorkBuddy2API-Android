package com.dsh.aihub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import android.content.Context
import com.dsh.aihub.m3.AppM3Theme
import com.dsh.aihub.UpdateScreen
import com.dsh.aihub.AboutScreen

/**
 * 应用外壳 —— 全 App 唯一的页面容器与导航（Material 3 版）。
 *
 * 相对 Miuix 原版的改动仅在**外观层**，导航语义完全保留：
 *   ① 底部导航从 Miuix NavigationBar 换成自建 Row（M3 的 NavigationBarItem
 *      会把每格撑到屏宽/6、固定 80dp 高，纯文字 Tab 显得太空旷）
 *   ② 顶栏从 SmallTopAppBar 换成 M3 CenterAlignedTopAppBar，返回按钮从文字变成图标
 *   ③ 图标用官方 material-icons-core（Icons.Filled.*）
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
    OVERVIEW("概览", Icons.Filled.Home),
    ACCOUNTS("账号", Icons.Filled.Person),
    APPS("应用", Icons.Filled.Build),
    SETTINGS("设置", Icons.Filled.Settings),
    GROWTH("成长", Icons.Filled.Star)
}
// 底栏从 6 个减到 5 个：「模型」不再是独立 Tab。
//
// 理由：它是一个纯列表（225 行，内容就是筛选 + 列表），却占掉 1/6 的底栏。
// 底栏位是全局最贵的资源 —— 换掉一个 Tab 意味着用户的空间记忆全部重建。
// 模型清单属于「查看有哪些可选模型」，是从概览页模型数量点进去的动作，
// 与「账号」「应用」这种需要长期切回来操作的对象不是一个量级。
// 图标来源：官方 material-icons-core 的 Icons.Filled.*。
//
// 这里换过一次方案，记下原因避免反复：
//   原先用手写 ImageVector（m3/M3Icons.kt，addPathNodes 解析 pathData 字符串），
//   目的是省掉 material-icons-extended 的 10MB。但那套图标放进
//   NavigationBarItem 的 icon 槽位后渲染不出来 —— 底栏只剩文字没有图标。
//   手写 ImageVector 与官方 Icon 的兼容性风险，比省下的体积重要得多。
//
// 用 core 而不是 extended：core 只有约 40 个常用图标、约 800KB；
// extended 是 2000+ 图标、35MB。本项目只需要 6 个。

/** 二级页面（覆盖在 Tab 之上，带返回按钮）。 */
enum class SubPage { NONE, USAGE, INSPECTOR, STORAGE, KEEPALIVE, PROMPT, RECORDS, SCHEDULE, UPDATE, ABOUT, QODER, MODELS, CONNECTIVITY }

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
    SubPage.MODELS -> "模型目录"
    SubPage.CONNECTIVITY -> "连通性自检"
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
                            // 之前用内联 ImageVector，用户反馈完全看不见（先怀疑纯黑
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
                        // 按"层级关系"分两种转场，不做统一处理：
                        //
                        //   Tab ↔ Tab   → 交叉淡化，无位移。
                        //     同级页面之间切换没有"进入方向"可言，滑动反而会让
                        //     用户以为页面有前后顺序。M3 的 NavigationBar 自己
                        //     就带选中指示器动画，内容区只需要一个短淡化。
                        //
                        //   Tab ↔ 二级页 → 横向推入/退出。
                        //     这是层级深入，滑动能表达"进去了一层"，返回时反向。
                        //
                        // 历史问题（都记下来避免再犯）：
                        //   ① 曾给 Tab 也加位移，还叠加 fadeIn/fadeOut，两层同时跑
                        //      → 观感是"整个屏幕在闪"。
                        //   ② 去掉淡化只留位移，但时长 220ms 配 1/8 屏宽，单位时间
                        //      位移过小 → 中间帧几乎没变化，观感是"卡住然后跳一下"。
                        //   根因是把"同级切换"和"层级切换"用了同一套动画。
                        val from = initialState
                        val to = targetState
                        val fromSub = from is PageKey.Sub
                        val toSub = to is PageKey.Sub

                        when {
                            // 同级 Tab：纯淡化，短时长
                            !fromSub && !toSub -> {
                                fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                            }
                            // 进出二级页：横向推入/退出 + 淡化
                            else -> {
                                val dir = if (toSub) 1 else -1
                                val enter = slideInHorizontally(
                                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                                ) { width -> dir * width / 4 } + fadeIn(tween(180))
                                val exit = slideOutHorizontally(
                                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                                ) { width -> -dir * width / 4 } + fadeOut(tween(120))
                                (enter togetherWith exit).apply {
                                    targetContentZIndex = if (toSub) 1f else -1f
                                }
                            }
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
                                SubPage.MODELS -> ModelsScreen(context, app)
                                SubPage.CONNECTIVITY -> ConnectivityScreen(context, app)
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
 * 底部导航栏 —— 直接用 Material 3 原生 [NavigationBar] + [NavigationBarItem]。
 *
 * 为什么改回原生（2026-10-29）：
 *   之前为了「6 个纯文字 Tab 不要被撑得太空」自建了 Row + selectable。
 *   那个判断本身没错，但代价是丢掉了原生组件自带的一整套东西：
 *     · 选中态指示器（pill）的动画
 *     · 图标/文字的垂直对齐与基线
 *     · 无障碍语义（selected / role 由组件内部处理）
 *     · 触摸目标与 Ripple 的 M3 规格
 *   自建版本只能逐个手写，写不全就显得"别扭"。
 *
 *   现在 Tab 恢复成「图标 + 文字」的标准形态，纯文字被撑空的问题自然消失，
 *   不需要再靠自建布局来压缩空白。
 *
 * 图标说明：AppTab 的 ImageVector 来自官方 material-icons-core。
 *   手写 ImageVector 在该槽位渲染不出来的问题已通过换官方图标解决。
 */
@Composable
private fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationBar {
        AppTab.entries.forEach { item ->
            NavigationBarItem(
                selected = selected == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label, maxLines = 1) },
            )
        }
    }
}
