package com.dsh.aihub

/**
 * 指纹清洗规则表 —— 从 [PromptInjection] 拆出来单独放，只因为规则里的字面量
 * 含尖括号与反斜杠，集中在一个文件里便于逐条核对。
 *
 * 对应 WorkBuddy 1.2.7 的 `a.I2.f419b`（标记词）、`f420c`（billing 头行）、
 * `f421d`（cc_ 键值）、`f422e`（模板句替换表）。
 *
 * ⚠️ 2026-10-29 修正：此前 [TEMPLATE_REPLACEMENTS] 的每一条都是「原文 → 原文」，
 * 即左侧误写成已替换后的文本，导致模板句清洗**实际零效力**（编译通过、逻辑空转）。
 * 现按 workbuddy2api-hub 的 SANITIZE_REWRITES 原文复原左右两侧。
 *
 * 更麻烦的是那条 `"one-to-many" to "one-to-many"`：它在
 * [FINGERPRINT_MARKERS] 里既是触发词又是"替换目标"，等于文本命中后什么也不改。
 * 已改为真实替换。
 */
internal object PromptRules {

    /**
     * 命中即认为「这段文本疑似携带第三方客户端指纹」的标记词。
     *
     * 为什么要先做这层粗筛：真正的清洗是逐条正则替换，对每条消息都跑一遍
     * 开销不小，而绝大多数消息根本不含指纹。先用纯字符串包含做一次廉价判断，
     * 不命中就直接返回原文（1.2.7 也是这个顺序）。
     *
     * ⚠️ 2026-10-29 修正：原先这里只有 Anthropic 系标记（billing 头 / Claude Code /
     * Codex），**没有身份句的任何触发词**。结果是
     * `You are an AI agent powered by X.` 这类句子不含任一标记词，被前置门禁
     * 直接 return，[RE_IDENTITY] 永远走不到 —— 身份句清洗一直是死代码。
     *
     * 现补上身份句的动词（与 [RE_IDENTITY] 的动词表一一对应）。粗筛只看
     * "有没有这些词"，真正是否命中交给正则，所以不会误伤。
     */
    val FINGERPRINT_MARKERS: List<String> = listOf(
        "x-anthropic-billing-header",
        "cc_",
        "You are Claude Code",
        "Anthropic's official CLI tool",
        "Main branch (",
        "You are a coding agent running in the Codex CLI",
        "github.com/anthropics/",
        "one-to-many",
        // 身份句触发词：与 RE_IDENTITY 的动词表保持一致。
        // 少一个就会让那种写法整条 bypass 掉门禁。
        "powered by",
        "built on",
        "built by",
        "created by",
        "developed by",
        "made by",
        "trained by"
    )

    /**
     * billing 头行：形如 `x-anthropic-billing-header: `
     * 整行（含换行）删除。
     */
    val RE_BILLING_HEADER = Regex("(?im)^[ \\t]*x-anthropic-billing-header[^\\n]*\\n?")

    /** cc_ 键值对整段（含尾部分号与空白）。 */
    val RE_CC_KV = Regex("(?i)\\bcc_[a-z0-9_]+=[^;\\n]*;?[ \\t]*")

    /**
     * 模板句替换表：命中即换成不含客户端品牌的中性说法。
     *
     * 取值与 workbuddy2api-hub 的 `SANITIZE_REWRITES` 逐条对齐（同一上游、
     * 社区实测有效的替换），仅做语言层面的等价整理：
     *
     *  - Claude Code 的自我介绍整句 → 通用助手（这是最硬的客户端指纹）
     *  - `Main branch (...)` 的括号说明 → 中性措辞
     *  - Codex CLI 的自我介绍整句 → 通用终端助手
     *  - Anthropic 的反馈指引（含 github 仓库地址）→ 指向服务提供方
     *
     * 注意：这里只做**字符串替换**，不是正则。上游的判据是「句子连续出现」，
     * 整句替换能同时打断句子结构与其中的品牌词，比逐词替换更彻底。
     */
    val TEMPLATE_REPLACEMENTS: List<Pair<String, String>> = listOf(
        // Claude Code 自我介绍
        "You are Claude Code, Anthropic's official CLI for Claude" to
            "You are a helpful software engineering assistant",
        // 分支说明
        "Main branch (you will usually use this for PRs)" to
            "Default branch (commonly used as the merge target)",
        // Codex CLI 自我介绍
        "You are a coding agent running in the Codex CLI, a terminal-based coding assistant." to
            "You are a helpful software engineering assistant running in a terminal.",
        // Anthropic 反馈指引（顺带去掉 github.com/anthropics/ 这个标记词）
        "To give feedback, users should report the issue at https://github.com/anthropics/claude-code/issues" to
            "To provide feedback, users should report the issue to the service provider.",
        // 上游错误码字样混进提示词时改成无害表述（原实现左右相同，实际空转）
        "11128" to "one-to-many"
    )

    /**
     * 身份句正则：`You are <…> powered by/built on/… <品牌>` 整句改中性。
     *
     * 为什么需要正则而不是固定串：同一类"经纬句"有大量变体，上游只补一条
     * 规则（比如新增 `built by`）就能让固定串匹配失效。workbuddy2api-hub
     * 的注释记录了这一点，并把动词表补全为 7 种。
     *
     * 窗口有界（`{0,3}` 个修饰词、`[^\n]*?` 不跨行），避免
     * "You are free to use tools built by …" 这类分词修饰宾语的句子被误伤。
     */
    val RE_IDENTITY = Regex(
        "You are\\s+" +
            "(?:an?\\s+|the\\s+)?(?:[\\w.-]+\\s+){0,3}?" +
            "(?:agent|assistant|model|AI|LLM)?[\\s,]*" +
            "(?:powered by|built on|built by|created by|developed by|made by|trained by)" +
            "[^\\n]*?(?:\\.(?=\\s|$)|(?=\\n|$))",
        RegexOption.IGNORE_CASE
    )

    /** [RE_IDENTITY] 的替换结果。 */
    const val IDENTITY_REPLACEMENT = "You are a helpful AI assistant."
}
