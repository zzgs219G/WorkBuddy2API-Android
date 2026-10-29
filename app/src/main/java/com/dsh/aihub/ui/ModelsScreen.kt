package com.dsh.aihub.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.aihub.AccountRegion
import com.dsh.aihub.m3.SectionTitle

/**
 * 模型页 —— 对应原纯 View 界面的 modelsPage()（已删除的 MainActivity.modelCard）。
 *
 * 关键修正（原实现遗漏导致国内版与国际版模型混在一起）：
 *   每个模型自带 `regions` 数组（`refreshAllModels` 会按账号所属版本注入，
 *   同一 id 在两边都存在时会合并成 ["domestic","international"]），
 *   因此必须按版本分组展示，并显示该模型可用的版本标签。
 *
 * 字段契约（与原 modelCard 完全对齐，不臆造字段名）：
 *   id / name / regions[] / supports_tools / supports_images / context_length / credits
 *   credits 为成本倍率：<=0 视为免费，缺失则不显示该行。
 */
@Composable
fun ModelsScreen(context: Context, app: AppState) {
    var query by remember { mutableStateOf("") }
    // 0=全部 1=仅国内 2=仅国际。用 Tab 而不是下拉：只有三态，直接可见更省一次点击。
    var regionFilter by remember { mutableStateOf(0) }
    val data = app.models

    val filtered: List<org.json.JSONObject> = remember(data, query, regionFilter, app.revision) {
        if (data == null) emptyList()
        else (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { model ->
                val regions = model.optJSONArray("regions") ?: org.json.JSONArray()
                val ids = (0 until regions.length()).map { regions.optString(it) }
                val matchRegion = when (regionFilter) {
                    1 -> ids.contains(AccountRegion.DOMESTIC.id)
                    2 -> ids.contains(AccountRegion.INTERNATIONAL.id)
                    else -> true
                }
                val matchQuery = query.isBlank() ||
                    model.optString("id").contains(query, ignoreCase = true) ||
                    model.optString("name").contains(query, ignoreCase = true)
                matchRegion && matchQuery
            }
    }

    // 分组：只能在一个版本用的模型归入该版本，两个版本都有的单列为"双版本可用"。
    val domestic = filtered.filter { m -> regionIds(m).let { it.contains("domestic") && !it.contains("international") } }
    val international = filtered.filter { m -> regionIds(m).let { !it.contains("domestic") && it.contains("international") } }
    val both = filtered.filter { m -> regionIds(m).let { it.contains("domestic") && it.contains("international") } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "模型目录") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索模型 ID 或名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                // 【M3 移植】Miuix 的 TabRow 是「tabs 列表 + selectedTabIndex + onTabSelected」，
                //   Material3 的 TabRow 则是内容槽（尾随 lambda 里自己放 Tab）。
                //   语义等价：selected 状态与点击回调逐项对应，选中索引仍由 regionFilter 驱动。
                val regionTabs = listOf("全部", "仅国内", "仅国际")
                TabRow(selectedTabIndex = regionFilter) {
                    regionTabs.forEachIndexed { index, label ->
                        Tab(
                            selected = regionFilter == index,
                            onClick = { regionFilter = index },
                            text = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                Button(
                    onClick = { app.refreshModels() },
                    enabled = !app.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (app.busy) "刷新中…" else "刷新模型目录") }
                if (data != null) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(
                        "共 ${filtered.size} 个（国内专属 ${domestic.size} · 国际专属 ${international.size} · 双版本 ${both.size}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (data == null) {
            item {
                PcCard {
                    Text(
                        "模型目录尚未加载。连接账号后点击「刷新模型目录」，将按账号版本分别拉取上游真实可用模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                PcCard {
                    Text("没有匹配模型，换个关键词或切换版本筛选试试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // 三个分组各自成段。分组为空时整段跳过，不留下空标题。
            if (both.isNotEmpty()) {
                item { SectionTitle(text = "双版本可用（${both.size}）") }
                items(both.size) { ModelCard(both[it]) }
            }
            if (domestic.isNotEmpty()) {
                item { SectionTitle(text = "国内版专属（${domestic.size}）") }
                items(domestic.size) { ModelCard(domestic[it]) }
            }
            if (international.isNotEmpty()) {
                item { SectionTitle(text = "国际版专属（${international.size}）") }
                items(international.size) { ModelCard(international[it]) }
            }
        }
    }
}

/** 取模型的可用版本 id 列表。 */
private fun regionIds(model: org.json.JSONObject): List<String> {
    val arr = model.optJSONArray("regions") ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }
}

/** 单个模型卡：名称 + 版本标签、ID、成本倍率、能力标签。 */
@Composable
private fun ModelCard(model: org.json.JSONObject) {
    // 【M3 移植】改用公共的 PcCard（已内含 M3 ElevatedCard + insideMargin 等价内边距），
    //   不再直接用 Card + insideMargin —— 保持与其它页面同一套卡片样式。
    PcCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter)
    ) {
        Column {
            Text(
                model.optString("name", model.optString("id")),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(2.dp))
            Text(
                model.optString("id"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 版本标签：明确写出该模型能在哪些版本上调用
            val labels = regionIds(model).map { AccountRegion.from(it).label }
            if (labels.isNotEmpty()) {
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "可用版本：${labels.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 成本倍率：字段缺失就不显示（不臆造为 0），<=0 明说免费
            if (model.has("credits") && !model.isNull("credits")) {
                val credits = model.optDouble("credits", Double.NaN)
                if (credits.isFinite()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (credits <= 0.0) "免费" else "成本 ×%.2f".format(credits),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val capabilities = buildList {
                if (model.optBoolean("supports_tools")) add("工具调用")
                if (model.optBoolean("supports_images")) add("图像输入")
                val context = model.optLong("context_length")
                if (context > 0) add("上下文 ${compactTokens(context)}")
            }
            if (capabilities.isNotEmpty()) {
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    capabilities.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 与原 formatTokens 保持同一写法（口径一致，避免同一数字两种显示）。 */
private fun compactTokens(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}
