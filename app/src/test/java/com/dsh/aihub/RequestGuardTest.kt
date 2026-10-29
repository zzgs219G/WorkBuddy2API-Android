package com.dsh.aihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RequestGuard] 的契约测试。
 *
 * 这组用例的核心是**不能误伤合法客户端**：
 * curl、OpenAI SDK、Cherry Studio、Claude Code 这些主流调用方式都必须被放行，
 * 否则用户会遇到「明明配对了却 403」。同时 DNS rebinding 必须被拦住。
 *
 * 每一条拒绝用例都配了一条相近的放行用例，用来固定边界。
 */
class RequestGuardTest {

    private val local = listOf("192.168.1.100")

    private fun isDeny(v: RequestGuard.Verdict) = v is RequestGuard.Verdict.Deny

    // ---------------------------------------------------------------- //
    // Host
    // ---------------------------------------------------------------- //

    @Test
    fun `missing host is allowed`() {
        // HTTP/1.0 与部分精简客户端不发 Host。它们跑在本机，构不成 rebinding。
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.checkHost("", local))
    }

    @Test
    fun `loopback hosts are allowed`() {
        for (h in listOf("127.0.0.1", "127.0.0.1:8788", "localhost", "localhost:8788", "[::1]:8788")) {
            assertEquals("应放行 $h", RequestGuard.Verdict.Allow, RequestGuard.checkHost(h, local))
        }
    }

    @Test
    fun `the device's own lan address is allowed as host`() {
        // 局域网调用时客户端会用手机 IP 作为 Host，这是合法的。
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.checkHost("192.168.1.100:8788", local))
    }

    @Test
    fun `a foreign host is rejected`() {
        // rebinding 的特征：请求到了本机，但客户端以为自己在访问 evil.example。
        val v = RequestGuard.checkHost("evil.example:8788", local)
        assertTrue(isDeny(v))
        assertTrue((v as RequestGuard.Verdict.Deny).reason.contains("192.168") ||
            v.reason.contains("局域网 IP"))
    }

    @Test
    fun `a host that looks like loopback but is not is rejected`() {
        // 前缀相同但不等价的域名不该被放过（避免用 startsWith 实现时的漏洞）。
        assertTrue(isDeny(RequestGuard.checkHost("127.0.0.1.evil.example", local)))
    }

    // ---------------------------------------------------------------- //
    // Origin
    // ---------------------------------------------------------------- //

    @Test
    fun `missing origin is allowed`() {
        // 关键用例：所有命令行/SDK 客户端都不发 Origin，绝不能因此被拒。
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.checkOrigin("", local))
    }

    @Test
    fun `loopback origins are allowed`() {
        assertEquals(
            RequestGuard.Verdict.Allow,
            RequestGuard.checkOrigin("http://127.0.0.1:8788", local),
        )
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.checkOrigin("http://localhost", local))
    }

    @Test
    fun `the device's own lan origin is allowed`() {
        assertEquals(
            RequestGuard.Verdict.Allow,
            RequestGuard.checkOrigin("http://192.168.1.100:8788", local),
        )
    }

    @Test
    fun `a cross site origin is rejected`() {
        // 恶意网页从 evil.example 直接 fetch 本机端口。
        val v = RequestGuard.checkOrigin("https://evil.example", local)
        assertTrue(isDeny(v))
        assertTrue((v as RequestGuard.Verdict.Deny).reason.contains("跨站"))
    }

    @Test
    fun `the null origin is rejected`() {
        // file:// 页面会发 Origin: null，属于典型攻击面。
        assertTrue(isDeny(RequestGuard.checkOrigin("null", local)))
    }

    @Test
    fun `a malformed origin is rejected rather than crashing`() {
        assertTrue(isDeny(RequestGuard.checkOrigin("garbage-without-scheme", local)))
    }

    @Test
    fun `an origin with a path is still parsed by host`() {
        assertEquals(
            RequestGuard.Verdict.Allow,
            RequestGuard.checkOrigin("http://127.0.0.1:8788/v1/chat/completions", local),
        )
    }

    // ---------------------------------------------------------------- //
    // 组合
    // ---------------------------------------------------------------- //

    @Test
    fun `a normal sdk request passes both checks`() {
        // 这就是 Cherry Studio / OpenAI SDK 实际发出的头：有 Host，无 Origin。
        val headers = mapOf("host" to "127.0.0.1:8788", "authorization" to "Bearer sk-x")
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.check(headers, local))
    }

    @Test
    fun `host failure short circuits before origin`() {
        val headers = mapOf("host" to "evil.example", "origin" to "https://good.example")
        assertTrue(isDeny(RequestGuard.check(headers, local)))
    }

    @Test
    fun `both missing is allowed`() {
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.check(emptyMap(), local))
    }

    @Test
    fun `no lan addresses still allows loopback clients`() {
        // 只连了移动数据时 localAddresses 为空，本机客户端不受影响。
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.check(emptyMap(), emptyList()))
        assertEquals(
            RequestGuard.Verdict.Allow,
            RequestGuard.check(mapOf("host" to "127.0.0.1:8788"), emptyList()),
        )
    }
}
