package com.dsh.aihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出网身份的版本分流。
 *
 * 背景：国际出口的渠道校验比国内严。上游对「自称官方 CLI 却缺关键身份头」的请求
 * 回 400 / code 11128（Illegal API invocation from an unapproved channel），
 * 而国内出口不校验，于是长期表现为「国内能用、国际版一调用就被拒」。
 *
 * 这组测试锁住两件事：
 *  1) 国际版必须补齐官方 CLI 的那几个头（否则渠道校验过不去）；
 *  2) 国内版不得因此多出官方没有的头（补齐不能变成「给国内凭空加料」）。
 */
class ClientIdentityTest {

    private val intl = ClientIdentity.pluginHeaders(AccountRegion.INTERNATIONAL)
    private val cn = ClientIdentity.pluginHeaders(AccountRegion.DOMESTIC)

    @Test
    fun `international sends deployment type, not the product name`() {
        // X-Product 是 deploymentType（SaaS），不是 productName（CodeBuddy）。
        // 发产品名等于落进一个产品自身枚举里不存在的值 —— 最硬的破绽。
        assertEquals("SaaS", intl["X-Product"])
        assertFalse("X-Product 必须是部署形态，不能是产品名", intl["X-Product"] == "CodeBuddy")
    }

    @Test
    fun `international sends the agent header family`() {
        assertEquals("conversation", intl["X-Agent-Purpose"])
        assertEquals("other", intl["X-Agent-Runtime"])
    }

    @Test
    fun `domestic keeps its existing header set`() {
        // 国内出口当前不校验这些项，改它只会引入未知风险，故维持原样。
        assertEquals("CodeBuddy", cn["X-Product"])
        assertFalse("国内版不应凭空多出 X-Agent-Purpose", cn.containsKey("X-Agent-Purpose"))
        assertFalse("国内版不应凭空多出 X-Agent-Runtime", cn.containsKey("X-Agent-Runtime"))
    }

    @Test
    fun `both regions keep the ide and product version headers`() {
        for (headers in listOf(intl, cn)) {
            assertEquals("CLI", headers["X-IDE-Type"])
            assertEquals("CLI", headers["X-IDE-Name"])
            assertEquals(ClientIdentity.CLI_VERSION, headers["X-IDE-Version"])
            assertEquals(ClientIdentity.CLI_VERSION, headers["X-Product-Version"])
        }
    }

    @Test
    fun `private data follows enterprise membership`() {
        assertEquals("false", ClientIdentity.privateDataHeader(""))
        assertEquals("true", ClientIdentity.privateDataHeader("ent-123"))
    }

    @Test
    fun `conversation request id is never empty`() {
        // 空头值会被 OkHttp/上游当成「缺失」，而官方 CLI 的该头恒有值。
        val headers = ClientIdentity.conversationHeaders("conv-1", "msg-1", "")
        assertEquals("msg-1", headers["X-Conversation-Request-ID"])
        assertEquals("msg-1", headers["X-Root-Request-ID"])

        val withTurn = ClientIdentity.conversationHeaders("conv-1", "msg-1", "turn-1")
        assertEquals("turn-1", withTurn["X-Conversation-Request-ID"])
        assertEquals("turn-1", withTurn["X-Root-Request-ID"])
        // 消息级 id 恒等，且不受 turn id 影响。
        assertEquals("msg-1", withTurn["X-Request-ID"])
        assertEquals("msg-1", withTurn["X-Conversation-Message-ID"])
    }

    @Test
    fun `stainless headers carry the timeout the official cli sends`() {
        val headers = ClientIdentity.stainlessHeaders()
        assertEquals("1200", headers["X-Stainless-Timeout"])
        assertEquals("js", headers["X-Stainless-Lang"])
        assertEquals("node", headers["X-Stainless-Runtime"])
        assertEquals("0", headers["X-Stainless-Retry-Count"])
    }

    @Test
    fun `trace headers are well formed`() {
        val headers = ClientIdentity.traceHeaders()
        val trace = headers["X-Trace-ID"]!!
        val span = headers["X-B3-SpanId"]!!

        assertEquals("trace 为 32 位 hex", 32, trace.length)
        assertTrue("trace 必须是 hex：$trace", trace.all { it in "0123456789abcdef" })
        assertEquals("span 为 16 位 hex", 16, span.length)
        // b3 四段：trace-span-1-parent（官方 CLI 是子 span，故为四段）
        assertEquals(4, headers["b3"]!!.split("-").size)
        assertEquals("00-$trace-$span-01", headers["traceparent"])
        assertEquals(trace, headers["X-B3-TraceId"])
        assertNotNull(headers["X-B3-ParentSpanId"])
        assertEquals("1", headers["X-B3-Sampled"])
    }

    @Test
    fun `trace headers differ per call`() {
        // 固定 trace id 反而是可识别特征，必须每次不同。
        assertFalse(ClientIdentity.traceHeaders()["X-Trace-ID"] ==
            ClientIdentity.traceHeaders()["X-Trace-ID"])
    }
}
