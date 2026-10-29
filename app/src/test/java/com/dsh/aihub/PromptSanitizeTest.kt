package com.dsh.aihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PromptInjection.sanitizeText] 的契约测试。
 *
 * 为什么必须有这组用例
 * --------------------
 * 这条链路上出过一次**静默失效**：[PromptRules.TEMPLATE_REPLACEMENTS] 的每条
 * 都是「原文 → 原文」（左侧误写成已替换后的文本），于是模板句清洗逻辑空转 ——
 * 编译通过、单元测试不存在、运行时没有任何报错，只是什么都没改。
 *
 * 这种错误只有用例能挡住：断言"输入里没有了指纹串"才能证明替换真的发生了。
 * 所以下面每条都断言**输出不再包含**某个指纹片段，而不是只断言"输出等于某个值"
 * （后者可以靠左右相同蒙混过关）。
 */
class PromptSanitizeTest {

    // ---------------------------------------------------------------- //
    // 模板句：替换必须真的发生
    // ---------------------------------------------------------------- //

    @Test
    fun `claude code self introduction is rewritten`() {
        val input = "You are Claude Code, Anthropic's official CLI for Claude. Help the user."
        val out = PromptInjection.sanitizeText(input)
        assertFalse("仍然包含 Claude Code 自我介绍", out.contains("Claude Code, Anthropic"))
        assertTrue("应换成中性表述", out.contains("helpful software engineering assistant"))
        // 句子后面的内容必须原样保留，不能整段吃掉
        assertTrue("后续内容丢失", out.contains("Help the user."))
    }

    @Test
    fun `codex cli self introduction is rewritten`() {
        val input = "You are a coding agent running in the Codex CLI, a terminal-based coding assistant. Be precise."
        val out = PromptInjection.sanitizeText(input)
        assertFalse(out.contains("Codex CLI"))
        assertTrue(out.contains("running in a terminal"))
        assertTrue("后续内容丢失", out.contains("Be precise."))
    }

    @Test
    fun `main branch parenthetical is rewritten`() {
        val input = "Main branch (you will usually use this for PRs) is stable."
        val out = PromptInjection.sanitizeText(input)
        assertFalse(out.contains("you will usually use this for PRs"))
        assertTrue(out.contains("Default branch"))
    }

    @Test
    fun `anthropic feedback link is rewritten, removing the github marker`() {
        val input = "To give feedback, users should report the issue at https://github.com/anthropics/claude-code/issues"
        val out = PromptInjection.sanitizeText(input)
        assertFalse("残留 github.com/anthropics/ 指纹", out.contains("github.com/anthropics/"))
        assertTrue(out.contains("service provider"))
    }

    @Test
    fun `the 11128 literal is replaced`() {
        // 原实现是 "11128" to "11128"，等于没改。
        val input = "error 11128 happened, and one-to-many"
        val out = PromptInjection.sanitizeText(input)
        assertFalse("11128 未被替换", out.contains("11128"))
        assertTrue(out.contains("one-to-many"))
    }

    // ---------------------------------------------------------------- //
    // 身份句：7 种动词变体都要覆盖
    // ---------------------------------------------------------------- //

    @Test
    fun `all identity verb variants are neutralised`() {
        // 上游只补一条规则就能让旧清洗失效，所以动词表必须一次补全。
        val verbs = listOf(
            "powered by", "built on", "built by",
            "created by", "developed by", "made by", "trained by",
        )
        for (verb in verbs) {
            val input = "You are an AI agent $verb SomeVendor Product. Then continue."
            val out = PromptInjection.sanitizeText(input)
            assertFalse("动词变体未被处理：$verb", out.contains(verb))
            assertTrue("应换成中性表述（$verb）", out.contains("helpful AI assistant"))
        }
    }

    @Test
    fun `a bare powered-by sentence without a noun is caught`() {
        // "You are powered by X." —— 省掉名词的形态也要命中。
        val input = "You are powered by DeepSeek Harness. Continue."
        val out = PromptInjection.sanitizeText(input)
        assertFalse(out.contains("powered by"))
    }

    @Test
    fun `a participle modifier mentioning built by is not touched`() {
        // 反例：这里 "built by" 修饰的是 tools，不是自我介绍。
        // 误伤会把用户正文改掉，比漏掉更糟。
        val input = "You are free to use tools built by the community one-to-many"
        val out = PromptInjection.sanitizeText(input)
        assertTrue("误伤了分词修饰句", out.contains("tools built by the community"))
    }

    // ---------------------------------------------------------------- //
    // 头行与键值：原本就生效，防止回归
    // ---------------------------------------------------------------- //

    @Test
    fun `billing header line is removed entirely`() {
        val input = "before\nx-anthropic-billing-header: cc_version=1.0; cc_entry=abc;\nafter"
        val out = PromptInjection.sanitizeText(input)
        assertFalse(out.contains("x-anthropic-billing-header"))
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after"))
    }

    @Test
    fun `cc key-value pairs are removed, including consecutive ones`() {
        // 连续多个 cc_ 键值：replace 只吃非重叠首批，实现里循环到稳定。
        val input = "cc_version=1.0; cc_entry=abc; cc_source=web; done"
        val out = PromptInjection.sanitizeText(input)
        assertFalse("残留 cc_ 键值：$out", out.contains("cc_version"))
        assertFalse(out.contains("cc_entry"))
        assertFalse(out.contains("cc_source"))
        assertTrue(out.contains("done"))
    }

    // ---------------------------------------------------------------- //
    // 不命中就走快路径
    // ---------------------------------------------------------------- //

    @Test
    fun `clean text is returned unchanged`() {
        val input = "Please summarise this document in three bullets."
        assertEquals(input, PromptInjection.sanitizeText(input))
    }

    @Test
    fun `rewriting is idempotent`() {
        // 同一段文本洗两遍结果必须相同：否则上游每次请求看到的提示词都在变，
        // 反而更像异常客户端。
        val input = "You are Claude Code, Anthropic's official CLI for Claude. cc_version=1;"
        val once = PromptInjection.sanitizeText(input)
        val twice = PromptInjection.sanitizeText(once)
        assertEquals(once, twice)
    }
}
