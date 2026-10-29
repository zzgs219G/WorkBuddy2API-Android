package com.dsh.aihub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Method

/**
 * 资源包到期时间解析的契约测试。
 *
 * 为什么必须有
 * ------------
 * 这条逻辑错了不会崩溃，只会静默给出错误结论，而且后果都是反的：
 *
 *   · 认错字段（用 CycleEndTime 而非 DeductionEndTime）→ 免费包每到月底被
 *     误判成「即将到期」→ 它们被优先派分，反而霸占所有请求
 *   · 不过滤哨兵值（免费包 DeductionEndTime 在 2034/2035 年）→ 同样的结果，
 *     而且是永久性的
 *   · 单位搞错（毫秒当秒）→ 所有包都显示成 1970 年或几万年后
 *
 * 这三条都来自 workbuddy2api-hub 对 31 行真实包数据的实测，不是推测。
 * 用反射测私有方法，因为它是纯函数、且不该为测试而放宽可见性。
 */
class CreditExpiryTest {

    private val now = 1_800_000_000.0  // 固定时刻，避免依赖真实时钟

    /** 调 NativeCore 的私有 expirySecondsOf。 */
    private fun expiry(pkg: JSONObject): Double? {
        val method: Method = NativeCore::class.java.getDeclaredMethod(
            "expirySecondsOf", JSONObject::class.java, Double::class.javaPrimitiveType
        )
        method.isAccessible = true
        val core = NativeCore
        return method.invoke(core, pkg, now) as Double?
    }

    private fun daysFromNow(days: Number): Long = ((now + days.toDouble() * 86400) * 1000).toLong()

    // ---------------------------------------------------------------- //
    // 字段优先级
    // ---------------------------------------------------------------- //

    @Test
    fun `DeductionEndTime wins over CycleEndTime`() {
        // 实测：免费包的 CycleEndTime 是每月周期边界，DeductionEndTime 才真正
        // 决定积分何时作废，两者能差 8 年。取错会把号误判成临期。
        val pkg = JSONObject()
            .put("DeductionEndTime", daysFromNow(10))
            .put("CycleEndTime", daysFromNow(3650))
        val result = expiry(pkg)
        assertNotNull(result)
        // 应该取 10 天后那个，不是 3650 天后
        assertEquals(10.0, (result!! - now) / 86400.0, 0.01)
    }

    @Test
    fun `CycleEndTime is the fallback when DeductionEndTime is absent`() {
        val pkg = JSONObject().put("CycleEndTime", daysFromNow(14))
        val result = expiry(pkg)
        assertNotNull(result)
        assertEquals(14.0, (result!! - now) / 86400.0, 0.01)
    }

    @Test
    fun `legacy field names still work`() {
        // ExpireTime / PackageEndTime / EndTime 是历史字段名，能取到就用。
        for (key in listOf("ExpireTime", "PackageEndTime", "EndTime")) {
            val result = expiry(JSONObject().put(key, daysFromNow(5)))
            assertNotNull("字段 $key 未被识别", result)
            assertEquals(5.0, (result!! - now) / 86400.0, 0.01)
        }
    }

    @Test
    fun `no expiry field yields null`() {
        assertNull(expiry(JSONObject().put("remaining", 100.0)))
    }

    @Test
    fun `zero and empty values are treated as absent`() {
        // 上游用 0 表示「不适用」，不能当成 1970 年（那会让它永远是最临期的）
        assertNull(expiry(JSONObject().put("DeductionEndTime", 0)))
        assertNull(expiry(JSONObject().put("DeductionEndTime", "")))
        assertNull(expiry(JSONObject().put("DeductionEndTime", "0")))
    }

    // ---------------------------------------------------------------- //
    // 哨兵值：这是最容易写错、后果最严重的一条
    // ---------------------------------------------------------------- //

    @Test
    fun `the never-expiring sentinel is reported as no expiry`() {
        // 免费包 / 体验版的 DeductionEndTime 落在 2034/2035（≈8~9 年后）。
        // 不过滤的话，这些号会永久命中「7 天内到期」分支，霸占所有请求 ——
        // 比不做临期优先还糟。
        val pkg = JSONObject().put("DeductionEndTime", daysFromNow(3000))
        assertNull("超过 730 天应视为不过期", expiry(pkg))
    }

    @Test
    fun `just under the sentinel boundary is still a real expiry`() {
        // 边界附近要保留真实时间：700 天是合理的长周期包，不该被当成哨兵。
        val pkg = JSONObject().put("DeductionEndTime", daysFromNow(700))
        assertNotNull(expiry(pkg))
    }

    @Test
    fun `a package expiring in three days is not mistaken for a sentinel`() {
        val result = expiry(JSONObject().put("DeductionEndTime", daysFromNow(3)))
        assertNotNull(result)
        assertEquals(3.0, (result!! - now) / 86400.0, 0.01)
    }

    // ---------------------------------------------------------------- //
    // 单位归一
    // ---------------------------------------------------------------- //

    @Test
    fun `milliseconds and seconds both normalise to the same instant`() {
        // 上游给的是 epoch 毫秒；历史接口有按秒返回的情况。用 1e11 分界。
        val ms = ((now + 3600) * 1000).toLong()
        val sec = (now + 3600).toLong()
        val fromMs = expiry(JSONObject().put("DeductionEndTime", ms))
        val fromSec = expiry(JSONObject().put("DeductionEndTime", sec))
        assertNotNull(fromMs)
        assertNotNull(fromSec)
        assertEquals(fromMs!!, fromSec!!, 1.0)
    }

    @Test
    fun `an already expired package still reports its real instant`() {
        // 已作废的包要能显示出真实时刻，界面据此标「已过期」。
        // 是否统计进账号级最早到期，由调用方按 remaining>0 决定。
        val result = expiry(JSONObject().put("DeductionEndTime", daysFromNow(-2)))
        assertNotNull(result)
        assertEquals(-2.0, (result!! - now) / 86400.0, 0.01)
    }
}
