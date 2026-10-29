package com.dsh.aihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FailureDiagnosis] 的契约测试。
 *
 * 为什么这组用例重要
 * ------------------
 * 这个类的全部价值在于「失效时说清楚为什么失效」。判断写错不会崩溃、不会报错，
 * 只会静默给出错误结论 —— 用户按错误结论去重新登录（其实是额度用完），
 * 或去等冷却（其实是上游改版），问题永远解决不了。
 *
 * 所以每条规则都要有正例（命中）+ 反例（不该命中），且反例要贴近真实的
 * 上游响应体，而不是随口编的字符串。
 */
class FailureDiagnosisTest {

    // ---------------------------------------------------------------- //
    // 额度不足
    // ---------------------------------------------------------------- //

    @Test
    fun `402 is always a credit failure`() {
        val d = FailureDiagnosis.fromHttp(402, "")
        assertEquals(FailureDiagnosis.Kind.HARD_CREDIT, d.kind)
        assertTrue(d.action.contains("换一个"))
    }

    @Test
    fun `credit wording in a 400 body is classified as credit, not client`() {
        // 上游额度不足有时回 400 而不是 402，只看状态码会误判成「请求被拒」。
        val body = """{"code":11101,"msg":"insufficient credit, please recharge"}"""
        val d = FailureDiagnosis.fromHttp(400, body)
        assertEquals(FailureDiagnosis.Kind.HARD_CREDIT, d.kind)
    }

    @Test
    fun `chinese credit wording is recognised`() {
        // 计费接口的国内措辞，不能被 lowercase 影响。
        val d = FailureDiagnosis.fromHttp(403, """{"msg":"积分不足"}""")
        assertEquals(FailureDiagnosis.Kind.HARD_CREDIT, d.kind)
    }

    // ---------------------------------------------------------------- //
    // 凭据失效
    // ---------------------------------------------------------------- //

    @Test
    fun `401 is a dead session`() {
        assertEquals(FailureDiagnosis.Kind.SESSION_DEAD, FailureDiagnosis.fromHttp(401, "").kind)
    }

    @Test
    fun `a 403 carrying the session marker is dead session, not rate limit`() {
        // 403 既能表示限流也能表示会话失效。只看状态码会把「该重新登录」
        // 误判成「该等冷却」，用户会一直等下去。
        val body = """{"code":12153,"msg":"Offline user session not found"}"""
        val d = FailureDiagnosis.fromHttp(403, body)
        assertEquals(FailureDiagnosis.Kind.SESSION_DEAD, d.kind)
    }

    @Test
    fun `a plain 403 without markers is a client rejection`() {
        val d = FailureDiagnosis.fromHttp(403, """{"msg":"developer role is not allowed"}""")
        assertEquals(FailureDiagnosis.Kind.CLIENT, d.kind)
        assertFalse("不应把普通 403 说成登录失效", d.action.contains("重新登录"))
    }

    // ---------------------------------------------------------------- //
    // 渠道校验（上游改版最典型的信号）
    // ---------------------------------------------------------------- //

    @Test
    fun `11128 is reported as upstream channel rejection, and says account is not the cause`() {
        val body = """{"code":11128,"msg":"Illegal API invocation from an unapproved channel"}"""
        val d = FailureDiagnosis.fromHttp(400, body)
        assertEquals(FailureDiagnosis.Kind.CLIENT, d.kind)
        // 这一条的关键信息就是「换号无效」，否则用户会白白换遍所有账号。
        assertTrue(d.action.contains("换号无效"))
    }

    @Test
    fun `conversation id missing is also a channel rejection`() {
        val body = """{"code":11128,"msg":"conversation id missing"}"""
        assertEquals(FailureDiagnosis.Kind.CLIENT, FailureDiagnosis.fromHttp(400, body).kind)
    }

    // ---------------------------------------------------------------- //
    // 限流 / 服务端 / 改版 / 未知
    // ---------------------------------------------------------------- //

    @Test
    fun `429 is rate limiting`() {
        val d = FailureDiagnosis.fromHttp(429, "")
        assertEquals(FailureDiagnosis.Kind.SOFT_RATE, d.kind)
        assertTrue(d.action.contains("冷却"))
    }

    @Test
    fun `5xx is a server failure`() {
        assertEquals(FailureDiagnosis.Kind.SERVER, FailureDiagnosis.fromHttp(502, "").kind)
        assertEquals(FailureDiagnosis.Kind.SERVER, FailureDiagnosis.fromHttp(500, "").kind)
    }

    @Test
    fun `404 points at an upstream change rather than user error`() {
        val d = FailureDiagnosis.fromHttp(404, "")
        assertEquals(FailureDiagnosis.Kind.NOT_FOUND, d.kind)
        assertTrue(d.summary.contains("上游接口不存在"))
        // 用户不该为 404 去改自己的任何配置。
        assertTrue(d.action.contains("不是你的操作问题"))
    }

    @Test
    fun `an unrecognised failure admits it is unrecognised`() {
        // 这是设计约束：不认识的失败不许编原因。
        val d = FailureDiagnosis.fromHttp(418, "")
        assertEquals(FailureDiagnosis.Kind.UNKNOWN, d.kind)
        assertTrue(d.summary.contains("未能识别"))
        assertTrue(d.action.contains("无法确定"))
    }

    // ---------------------------------------------------------------- //
    // 上游原文提取
    // ---------------------------------------------------------------- //

    @Test
    fun `displayMsg zh is preferred over raw body`() {
        val body = """{"code":11101,"displayMsg":{"zh":"积分不足，请充值","en":"no credit"},"msg":"fallback"}"""
        val d = FailureDiagnosis.fromHttp(402, body)
        assertEquals("积分不足，请充值", d.upstreamMessage)
    }

    @Test
    fun `non json body is truncated rather than dropped`() {
        val body = "x".repeat(1000)
        val d = FailureDiagnosis.fromHttp(500, body)
        // 保留原文是为了日后对照上游是否改版；截断是为了不把整页 HTML 塞进数据库。
        assertEquals(300, d.upstreamMessage.length)
    }

    @Test
    fun `empty body yields empty upstream message`() {
        assertEquals("", FailureDiagnosis.fromHttp(500, "   ").upstreamMessage)
    }

    // ---------------------------------------------------------------- //
    // 网络层
    // ---------------------------------------------------------------- //

    @Test
    fun `timeout is explained as timeout, with keepalive hint`() {
        val d = FailureDiagnosis.fromNetwork(java.net.SocketTimeoutException("Read timed out"))
        assertEquals(FailureDiagnosis.Kind.NETWORK, d.kind)
        assertTrue(d.action.contains("超时"))
    }

    @Test
    fun `dns failure points at network or proxy`() {
        val d = FailureDiagnosis.fromNetwork(java.net.UnknownHostException("Unable to resolve host"))
        assertEquals(FailureDiagnosis.Kind.NETWORK, d.kind)
        assertTrue(d.action.contains("DNS") || d.action.contains("域名"))
    }

    @Test
    fun `connection reset suggests retry`() {
        val d = FailureDiagnosis.fromNetwork(java.io.IOException("Connection reset by peer"))
        assertEquals(FailureDiagnosis.Kind.NETWORK, d.kind)
        assertTrue(d.action.contains("重试"))
    }

    // ---------------------------------------------------------------- //
    // 无可用账号
    // ---------------------------------------------------------------- //

    @Test
    fun `no accounts at all asks the user to log in`() {
        val d = FailureDiagnosis.fromNoAccount(total = 0, cooling = 0)
        assertEquals(FailureDiagnosis.Kind.NO_ACCOUNT, d.kind)
        assertTrue(d.action.contains("登录"))
    }

    @Test
    fun `all accounts cooling asks the user to wait, not to log in`() {
        // 这两种情况以前共用一句「没有可用的账号」，用户会去重新登录而白费功夫。
        val d = FailureDiagnosis.fromNoAccount(total = 2, cooling = 2)
        assertEquals(FailureDiagnosis.Kind.NO_ACCOUNT, d.kind)
        assertTrue(d.summary.contains("冷却"))
        assertFalse("冷却不该建议重新登录", d.action.contains("登录"))
    }

    @Test
    fun `counting is reported so the user can see how bad it is`() {
        val d = FailureDiagnosis.fromNoAccount(total = 5, cooling = 1)
        assertTrue(d.summary.contains("1/5"))
    }

    // ---------------------------------------------------------------- //
    // 账号风控 11140 / 模型级限制 6004（依据上游实测补充）
    // ---------------------------------------------------------------- //

    @Test
    fun `11140 is account restriction, and must NOT suggest switching accounts`() {
        // 上游实测：11140 按设备关联，换号只会把更多账号一起送进风控。
        // 如果这里建议"换号"，用户照做就会连累其它账号 —— 这是最危险的一条错判。
        val body = """{"code":11140,"msg":"账号存在异常行为，已被限制使用"}"""
        val d = FailureDiagnosis.fromHttp(403, body)
        assertEquals(FailureDiagnosis.Kind.ACCOUNT_RESTRICTED, d.kind)
        assertTrue("应说明要等", d.action.contains("等待") || d.action.contains("停止"))
        assertTrue("必须警告不要换号", d.action.contains("不要换号"))
        assertFalse("不能建议重新登录", d.action.contains("重新登录"))
    }

    @Test
    fun `11140 is recognised by code alone, without the chinese message`() {
        val d = FailureDiagnosis.fromHttp(403, """{"code":11140}""")
        assertEquals(FailureDiagnosis.Kind.ACCOUNT_RESTRICTED, d.kind)
    }

    @Test
    fun `a longer number containing 11140 is not misread`() {
        // 裸子串匹配会让 111400 命中断言，进错分支。
        val d = FailureDiagnosis.fromHttp(400, """{"code":111400}""")
        assertFalse(
            "111400 被误判成 11140",
            d.kind == FailureDiagnosis.Kind.ACCOUNT_RESTRICTED,
        )
    }

    @Test
    fun `6004 is model limited, and must NOT suggest switching accounts`() {
        // 6004 只影响一个模型；按账号冷却会让一个撞限流的模型黑洞掉整个池。
        val body = """{"code":6004,"msg":"usage exceeds frequency limit"}"""
        val d = FailureDiagnosis.fromHttp(429, body)
        assertEquals(FailureDiagnosis.Kind.MODEL_LIMITED, d.kind)
        assertTrue("应建议换模型", d.action.contains("模型"))
        assertTrue("必须警告不要换账号", d.action.contains("不要因此换账号"))
    }

    @Test
    fun `model-scoped codes are all classified as model limited`() {
        // 11102 服务信息缺失 / 11134 provider 暂不可用 / 11150 reasoning effort 不支持 / 11158 模型不可用
        for (code in listOf(11102, 11134, 11150, 11158)) {
            val d = FailureDiagnosis.fromHttp(400, """{"code":$code}""")
            assertEquals("code $code 应归为模型级", FailureDiagnosis.Kind.MODEL_LIMITED, d.kind)
        }
    }

    @Test
    fun `genuine credit exhaustion is not misread as model limit`() {
        // 两个分支的文案里都可能出现 frequency limit，只有"账号没钱"才是 HARD_CREDIT。
        val body = """{"code":402,"msg":"insufficient credit, usage exceeds frequency limit"}"""
        val d = FailureDiagnosis.fromHttp(402, body)
        assertEquals(FailureDiagnosis.Kind.HARD_CREDIT, d.kind)
    }

    @Test
    fun `one-to-many is treated as a channel rejection`() {
        // 10-28 起上游把「A 句 + B 句连续出现」也返回 code one-to-many。
        val body = """{"code":"one-to-many","msg":"request illegal"}"""
        val d = FailureDiagnosis.fromHttp(400, body)
        assertEquals(FailureDiagnosis.Kind.CLIENT, d.kind)
        assertTrue(d.action.contains("换号无效"))
    }

    // ---------------------------------------------------------------- //
    // 入库格式
    // ---------------------------------------------------------------- //

    @Test
    fun `record line carries kind, summary and action`() {
        val line = FailureDiagnosis.toRecordLine(FailureDiagnosis.fromHttp(429, ""))
        assertTrue(line.startsWith("[soft_rate]"))
        assertTrue(line.contains("建议："))
    }

    @Test
    fun `record line omits the action section when there is no advice`() {
        val line = FailureDiagnosis.toRecordLine(
            FailureDiagnosis.Diagnosis(FailureDiagnosis.Kind.UNKNOWN, "something", "")
        )
        assertEquals("[unknown] something", line)
    }
}
