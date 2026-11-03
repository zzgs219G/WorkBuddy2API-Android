package com.dsh.aihub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今日按账号消耗」排序规则的契约测试。
 *
 * 为什么必须有
 * ------------
 * 这一屏（概览「账号池」/ 记录页「今日按账号」）的全部价值就是**排序**：
 * 用户扫一眼是为了找"谁在烧钱"。排序错了程序不崩、数字也对，
 * 但结论会反过来 —— 最能花的那个号沉到底部，等于这一屏白给。
 *
 * 三条规则：
 *   ① 消耗多的在前（主键）
 *   ② 消耗相同看请求数（同为 0 消耗时，谁在跑得多）
 *   ③ 再相同按 uid 稳定排序（避免每次重组行序跳动，Compose 里会让用户看花眼）
 *
 * 另外锁定一个边界：未归属请求（account_uid 为空，被网关拒绝的那类）
 * credits 恒为 0、请求数也计入，因此会自然沉底而不需要特判 —— 这条也测。
 */
class UsageByAccountSortTest {

    private fun row(uid: String, credits: Double, requests: Int): JSONObject =
        JSONObject()
            .put("account_uid", uid)
            .put("credits", credits)
            .put("requests", requests)

    private fun sort(vararg rows: JSONObject): List<String> =
        NativeStore.sortUsageByAccount(JSONArray(rows.toList())).map { it.optString("account_uid") }

    @Test
    fun `按消耗降序`() {
        assertEquals(
            listOf("c", "a", "b"),
            sort(row("a", 5.0, 10), row("b", 1.0, 100), row("c", 20.0, 2))
        )
    }

    @Test
    fun `消耗相同时请求多的在前`() {
        assertEquals(
            listOf("y", "x"),
            sort(row("x", 3.0, 1), row("y", 3.0, 9))
        )
    }

    @Test
    fun `消耗与请求数都相同时按 uid 稳定排序`() {
        // 稳定排序的意义：Compose 每次重组都重算这一列，
        // 顺序不稳定会导致行内容在视觉上"跳来跳去"。
        assertEquals(
            listOf("a", "b", "c"),
            sort(row("c", 1.0, 1), row("a", 1.0, 1), row("b", 1.0, 1))
        )
    }

    @Test
    fun `未归属请求沉到最底`() {
        assertEquals(
            listOf("real", ""),
            sort(row("", 0.0, 7), row("real", 0.5, 1))
        )
    }

    @Test
    fun `空数组不抛异常`() {
        assertTrue(NativeStore.sortUsageByAccount(JSONArray()).isEmpty())
    }

    @Test
    fun `缺失字段按零处理而不是抛出`() {
        // 上游/数据库都可能给不出某个字段，排行逻辑必须能吞掉残缺数据：
        // 这里只保证"不抛异常且长度不丢"。
        val rows = JSONArray().put(JSONObject().put("account_uid", "only_uid"))
        val result = NativeStore.sortUsageByAccount(rows)
        assertEquals(1, result.size)
        assertEquals("only_uid", result[0].optString("account_uid"))
    }
}
