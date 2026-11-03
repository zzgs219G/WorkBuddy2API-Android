package com.dsh.aihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到账本语义的契约测试。
 *
 * 为什么必须有
 * ------------
 * 签到账本错了不会崩，只会静默地把「花掉的钱」记成「赚到的钱」，
 * 或者让同一笔收益被记两遍。用户看到的现象是「收支对不上」，而代码看起来完全正常：
 *
 *   · already（今日已签到）时仍按快照差记账 → 上游没发放，那个差额是期间的
 *     调用消耗 → 记成 checkin 收益就是无中生有
 *   · 快照差为负仍记账 → ApiHostService 已经为这笔消耗记过 kind=consume，
 *     再记一个负数就是双重计算，且「签到」这类目会永久显示为亏损
 *   · 同一账号同一天重复记账 → 自动签到 + 手动签到各记一次，条数虚高
 *
 * 这里测的是 [checkinLedgerAmount] 这个纯函数（把上面三条规则收敛到一处），
 * 而不是整个 checkIn（它要联网、写库，属集成测试范畴）。
 */
class CheckinLedgerTest {

    @Test
    fun `正常到账记真实差额`() {
        assertEquals(
            8.0,
            NativeCore.checkinLedgerAmount(already = false, gained = 8.0),
            0.0001
        )
    }

    @Test
    fun `今日已签到时不记收益`() {
        // 上游没有发放动作，快照差只反映期间的调用消耗 —— 不能记成签到收益。
        assertEquals(
            0.0,
            NativeCore.checkinLedgerAmount(already = true, gained = 8.0),
            0.0001
        )
    }

    @Test
    fun `负差额记零而不是记亏损`() {
        // 消耗已由 ApiHostService 按 kind=consume 记过，这里再记负数就是双重计算。
        assertEquals(
            0.0,
            NativeCore.checkinLedgerAmount(already = false, gained = -12.5),
            0.0001
        )
    }

    @Test
    fun `查不到额度时记零`() {
        assertEquals(
            0.0,
            NativeCore.checkinLedgerAmount(already = false, gained = null),
            0.0001
        )
    }

    @Test
    fun `零差额记零`() {
        // 上游次日到账 / 按批次发放时差额为 0。记 0 让"今天签到过"在账本里可见，
        // 但不会让收支数字虚高。
        assertEquals(
            0.0,
            NativeCore.checkinLedgerAmount(already = false, gained = 0.0),
            0.0001
        )
    }

    @Test
    fun `非有限值不记账`() {
        // 上游返回 NaN/Infinity 时（JSON 里可能出现），写库会变成 NULL 并触发约束。
        assertEquals(0.0, NativeCore.checkinLedgerAmount(already = false, gained = Double.NaN), 0.0001)
        assertEquals(0.0, NativeCore.checkinLedgerAmount(already = false, gained = Double.POSITIVE_INFINITY), 0.0001)
    }

    @Test
    fun `收益类账目永不记负数`() {
        // 把范围扫一遍，确保任何输入都不会产出负值 —— 这是这条规则的核心不变式。
        val inputs = listOf(null, -100.0, -1.0, -0.0001, 0.0, 0.0001, 1.0, 100.0, Double.NaN)
        for (gained in inputs) {
            for (already in listOf(true, false)) {
                val amount = NativeCore.checkinLedgerAmount(already, gained)
                assertTrue(
                    "checkin 账目金额不应为负：already=$already gained=$gained -> $amount",
                    amount >= 0.0
                )
            }
        }
    }
}
