package com.dsh.aihub

import org.json.JSONObject

/**
 * 上游失败的分类与诊断。
 *
 * 为什么需要这个文件
 * ------------------
 * 改动前，上游任何非 2xx 都会被打成同一个 `upstream_error`，用户看到的只是一句
 * 上游原文（或「上游返回 HTTP 500」）。后果：
 *
 *   · 分不清「账号积分用完了」和「上游改了校验规则」——两者都只是 400/403；
 *   · 分不清「这个号被限流」和「这个号凭据失效」——处理方式完全不同
 *     （等 5 分钟 vs 重新登录）；
 *   · 出了事只能翻 logcat，且下一次请求不一定复现（尤其轮换账号场景）。
 *
 * 本文件只做一件事：把 `(HTTP 状态码, 响应体, 是否有可用账号)` 映射成一个
 * **可读的中文结论 + 建议动作**，并给出稳定的机器可读 kind，供调用记录入库、
 * 界面聚合统计。
 *
 * 设计约束（重要）
 * ----------------
 *  1) 只做判断，不发请求、不读磁盘、不改任何状态 —— 纯函数，可单测。
 *  2) 不猜上游意图。凡是规则不明确的，归到 [Kind.CLIENT]（请求/自身问题）或
 *     [Kind.UNKNOWN]，并在文案里如实说明"无法确定"，而不是编一个原因。
 *  3) 措辞面向"使用者"，不是开发者。每个结论都要能回答：
 *     「我现在该做什么」。
 */
object FailureDiagnosis {

    /**
     * 失败类型。枚举值会入库（usage_logs.error_kind），不要随意改名。
     *
     * 命名与 WorkBuddy-Hub 的 Wire.ErrorKind 对齐，便于两边对照排查。
     */
    enum class Kind(val wire: String, val label: String) {
        /** 积分 / 额度 / 余额不足。换号可能有效，重试无效。 */
        HARD_CREDIT("hard_credit", "额度不足"),

        /** 被限流。等冷却结束即可，换号可绕过。 */
        SOFT_RATE("soft_rate", "被限流"),

        /** 账号凭据失效（access/refresh token 过期或被吊销）。必须重新登录。 */
        SESSION_DEAD("session_dead", "登录已失效"),

        /**
         * 账号级风控限制（HTTP 403 / code 11140「账号存在异常行为，已被限制使用」）。
         *
         * 与 [SESSION_DEAD] 的区别很关键：这不是登录失效，重新登录没用；
         * 也不是内容拦截，换提示词没用。
         *
         * workbuddy2api-hub 的实测记录：上游按小时到工作日计，官方工单口径是
         * 「临时风控 1-3 个工作日自动解除」，期间**官方客户端同样报这个错**。
         * 且明确警告「解封前严禁换号继续打同一设备」—— 换号只会把更多账号
         * 一起送进风控。所以建议必须是"停手等"，而不是"换号"。
         */
        ACCOUNT_RESTRICTED("account_restricted", "账号被风控限制"),

        /**
         * 模型级不可用 / 该模型额度用满（6004 / 11134 / 11102 / 11150 / 11158）。
         *
         * 与 [HARD_CREDIT] 的区别：那是整个账号没钱，这里是**某个模型**不能用，
         * 同账号的其它模型仍然正常。所以处置是"换模型"而不是"换账号"。
         */
        MODEL_LIMITED("model_limited", "该模型不可用或额度用满"),

        /** 上游拒绝请求体：多为角色/字段/内容校验。改请求或改提示词。 */
        CLIENT("client", "请求被上游拒绝"),

        /** 上游接口路径不存在 —— 通常意味着上游改版。 */
        NOT_FOUND("not_found", "上游接口不存在"),

        /** 上游 5xx。多为上游故障，稍后重试。 */
        SERVER("server", "上游服务异常"),

        /** 网络层失败：DNS、连接被重置、超时。 */
        NETWORK("network", "网络不通"),

        /** 本地没有可用账号（全部冷却中或未登录）。 */
        NO_ACCOUNT("no_account", "无可用账号"),

        /** 无法归类。文案里如实说明未识别，不编原因。 */
        UNKNOWN("unknown", "未识别的失败"),
    }

    /**
     * 一次失败诊断的结论。
     *
     * @param kind 机器可读分类
     * @param summary 一句话结论（给用户看，含 HTTP 码便于对账）
     * @param action 建议动作（用户该做什么；无建议时为空串）
     * @param upstreamMessage 上游原文（已截断），保留用于对照上游是否改版
     */
    data class Diagnosis(
        val kind: Kind,
        val summary: String,
        val action: String,
        val upstreamMessage: String = "",
    ) {
        /** 入库用的一行摘要：`[kind] summary`。 */
        fun toRecord(): String = "[${kind.wire}] $summary"
    }

    // ---------------------------------------------------------------- //
    // 判定依据
    // ---------------------------------------------------------------- //

    /**
     * 额度不足的措辞。
     *
     * 依据：Hub 的 Wire.HARD_CREDIT_MARKERS（同一上游、同一批实测），
     * 本项目额外补充了中文计费接口的措辞。匹配时不区分大小写。
     */
    private val CREDIT_MARKERS = listOf(
        "insufficient credit", "no credit", "credit exhausted", "credits exhausted",
        "out of credit", "quota exceeded", "quota exhaust", "payment required",
        "credit not enough", "not enough credit",
        "积分不足", "额度不足", "余额不足", "积分用完", "额度用尽", "没有积分",
    )

    /**
     * 凭据失效的措辞。
     *
     * "12153" 与 "Offline user session not found" 来自 Hub 的实测（403 也可能表示会话失效，
     * 不能只看 401）。
     */
    private val SESSION_DEAD_MARKERS = listOf(
        "offline user session not found", "12153",
        "token expired", "token invalid", "invalid token", "unauthorized",
        "登录已失效", "凭证过期", "请重新登录",
    )

    /**
     * 上游改版 / 渠道拒绝的措辞。
     *
     * 11128 是上游「request illegal from an unapproved channel」的固定码 ——
     * 它既可能是系统提示指纹被拦，也可能是缺了必需的头或会话标识。
     * 这类失败**不是账号问题**，换号无效，必须改客户端行为。
     */
    private val CHANNEL_MARKERS = listOf(
        "11128", "unapproved channel", "illegal api invocation",
        "conversation id missing",
        // 10-28 起上游把「A 句 + B 句连续出现」也归到同一判据，返回 code one-to-many。
        // 它不是单词匹配，而是整段连续匹配（见 PromptRules 的说明）。
        "one-to-many",
    )

    /**
     * 账号被风控的措辞（HTTP 403 / code 11140）。
     *
     * 「账号存在异常行为，已被限制使用」是上游原文。与 11128 的区别在于：
     * 11128 是"这个请求不像官方客户端发的"，11140 是"这个账号被盯上了"。
     * 后者换提示词、换请求头都没用，只能等。
     */
    private val RESTRICTED_MARKERS = listOf(
        "账号存在异常行为",
        "已被限制使用",
        "account restricted",
    )

    /** 11140 单独走按码匹配（避免命中 111400 之类更长的数字）。 */
    private const val RESTRICTED_CODE = "11140"

    /**
     * 模型级不可用的 code。
     *
     * 依据 workbuddy2api-hub 的 MODEL_SCOPED_ERROR_CODES 与实测注释：
     *   6004  usage exceeds frequency limit（该模型窗口额度用满）
     *   11102 service info not found
     *   11134 model provider temporarily unavailable
     *   11150 reasoning effort unsupported
     *   11158 model not available
     * 共同点：**只影响这一个模型**，同账号其它模型仍可用。
     */
    private val MODEL_LIMITED_CODES = listOf("6004", "11102", "11134", "11150", "11158")

    /** 6004 的措辞（有些响应体不带 code，只有文案）。 */
    private val MODEL_LIMITED_MARKERS = listOf(
        "usage exceeds frequency limit",
        "model limit",
    )

    /** 从响应体里取可用于展示的上游原文。 */
    private fun upstreamText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        // 优先上游的本地化提示：displayMsg.zh / displayMsg.en / msg
        if (trimmed.startsWith("{")) {
            runCatching {
                val obj = JSONObject(trimmed)
                obj.optJSONObject("displayMsg")?.let { display ->
                    val zh = display.optString("zh").trim()
                    if (zh.isNotEmpty()) return zh
                    val en = display.optString("en").trim()
                    if (en.isNotEmpty()) return en
                }
                val msg = obj.optString("msg").trim()
                if (msg.isNotEmpty()) return msg
                obj.optJSONObject("error")?.let { err ->
                    val message = err.optString("message").trim()
                    if (message.isNotEmpty()) return message
                }
            }.onFailure { /* 不是 JSON，落到下面截断原文 */ }
        }
        return trimmed.take(300)
    }

    /** 逐一尝试匹配措辞。大小写不敏感，同时比对原文（中文场景 lowercase 无影响）。 */
    private fun matches(body: String, markers: List<String>): Boolean {
        val lowered = body.lowercase()
        return markers.any { lowered.contains(it.lowercase()) }
    }

    /**
     * 匹配错误码。
     *
     * 不能直接用 [matches] 做裸子串包含：`6004` 会命中 `16004`、`160045` 这类
     * 更长的数字，`11140` 也会命中 `111400`。所以要求数字两侧不是数字字符。
     */
    private fun matchesAnyCode(body: String, codes: List<String>): Boolean =
        codes.any { code ->
            Regex("(?<![0-9])$code(?![0-9])").containsMatchIn(body)
        }

    // ---------------------------------------------------------------- //
    // 主入口
    // ---------------------------------------------------------------- //

    /**
     * 诊断一次 HTTP 失败的响应。
     *
     * @param status HTTP 状态码
     * @param body 响应体原文（可为空）
     */
    fun fromHttp(status: Int, body: String): Diagnosis {
        val text = upstreamText(body)

        // 顺序有意：先看语义明确的措辞，再看状态码。
        // 因为同一状态码能表达不同含义（403 既可能是限流也可能是会话失效），
        // 只看状态码必然误判。
        //
        // 账号风控放在最前：它和 11128、限流都可能同为 403/400，
        // 但处置方式唯一且相反（必须停手，换号有害），所以优先级最高。
        if (matches(body, RESTRICTED_MARKERS) || matchesAnyCode(body, listOf(RESTRICTED_CODE))) {
            return Diagnosis(
                Kind.ACCOUNT_RESTRICTED,
                "账号被上游风控限制（HTTP $status，11140）",
                "立即停止使用这个账号，等待 1-3 个工作日自动解除。" +
                    "期间**不要换号继续打同一台设备** —— 上游按设备关联，" +
                    "换号只会把更多账号一起送进风控。官方客户端此刻同样报此错，" +
                    "所以这不是配置或提示词问题，改任何设置都无效。",
                text,
            )
        }
        // 模型级限制放在额度判定之前：6004 的文案里也有 "frequency limit"，
        // 但它是"这个模型"的窗口额度，与"整个账号没钱"处置完全不同。
        //
        // 判据分两种：带 code 的（6004/11102/11134/11150/11158）与只有文案的
        // （usage exceeds frequency limit）。后者若同时命中额度措辞，说明是账号
        // 真没钱，交给下面的 HARD_CREDIT 分支处理。
        if (matchesAnyCode(body, MODEL_LIMITED_CODES) ||
            (matches(body, MODEL_LIMITED_MARKERS) && !matches(body, CREDIT_MARKERS))
        ) {
            return Diagnosis(
                Kind.MODEL_LIMITED,
                "该模型的额度/频率已达上限（HTTP $status）",
                "换用同账号的其它模型即可，账号本身没问题；" +
                    "等模型窗口重置后该模型会自动恢复。不要因此换账号 —— " +
                    "按账号冷却会把整个账号池拖黑。",
                text,
            )
        }
        if (matches(body, CHANNEL_MARKERS)) {
            return Diagnosis(
                Kind.CLIENT,
                "上游拒绝了这个请求（HTTP $status，渠道校验）",
                "不是账号问题，换号无效。通常是上游收紧了客户端校验：先升级到最新版本，" +
                    "若已最新则等待作者适配。可到「出网取证」导出实际发出的请求头用于比对。",
                text,
            )
        }
        if (status == 402 || matches(body, CREDIT_MARKERS)) {
            return Diagnosis(
                Kind.HARD_CREDIT,
                "账号额度已用尽（HTTP $status）",
                "换一个还有额度的账号，或等签到/额度刷新。重试同一个号不会有变化。",
                text,
            )
        }
        if (status == 401 || matches(body, SESSION_DEAD_MARKERS)) {
            return Diagnosis(
                Kind.SESSION_DEAD,
                "账号登录已失效（HTTP $status）",
                "到「账号」页重新登录该账号。若是国际版账号，还需先在官方客户端完成试用激活。",
                text,
            )
        }

        return when {
            status == 429 -> Diagnosis(
                Kind.SOFT_RATE,
                "账号被上游限流（HTTP 429）",
                "等待冷却结束（界面上该账号会显示冷却剩余时间），或换另一个账号。已自动指数退避。",
                text,
            )

            // 404 单独处理：它是"上游改版"最典型的信号。
            // /v2/chat/completions 这个路径一直存在；一旦 404，多半是上游换了路径或域名。
            status == 404 -> Diagnosis(
                Kind.NOT_FOUND,
                "上游接口不存在（HTTP 404）",
                "大概率是上游改版换了接口路径或域名。这不是你的操作问题，" +
                    "需要作者更新代码；可先到「出网取证」确认实际请求的 URL。",
                text,
            )

            status >= 500 -> Diagnosis(
                Kind.SERVER,
                "上游服务异常（HTTP $status）",
                "上游自身故障，稍后重试。已自动换号重试过一次。",
                text,
            )

            status == 400 || status == 403 || status == 422 -> Diagnosis(
                Kind.CLIENT,
                "上游拒绝了请求内容（HTTP $status）",
                "通常是请求体字段、角色（developer/system）或提示词触发了上游校验。" +
                    "若只在使用某个特定客户端时出现，试试在设置里开启提示词注入降级。",
                text,
            )

            else -> Diagnosis(
                Kind.UNKNOWN,
                "上游返回 HTTP $status（未能识别具体原因）",
                "这条失败原因无法确定，请不要据此改动任何配置。可导出「出网取证」后反馈。",
                text,
            )
        }
    }

    /**
     * 诊断网络层异常（连接超时、DNS、连接被重置）。
     *
     * @param throwable 捕获到的异常
     */
    fun fromNetwork(throwable: Throwable): Diagnosis {
        val name = throwable.javaClass.simpleName
        val detail = throwable.message.orEmpty().take(200)
        val hint = when {
            detail.contains("timed out", ignoreCase = true) ||
                name.contains("Timeout", ignoreCase = true) ->
                "请求超时。可能是手机网络不稳、上游响应慢，或服务被系统冻结（检查悬浮球/电池白名单是否在生效）。"

            detail.contains("reset", ignoreCase = true) ||
                detail.contains("broken pipe", ignoreCase = true) ->
                "连接被重置。多为网络切换或上游主动断开，重试即可。"

            detail.contains("Unable to resolve host", ignoreCase = true) ||
                detail.contains("UnknownHost", ignoreCase = true) ||
                name.contains("UnknownHost", ignoreCase = true) ->
                "无法解析上游域名。检查手机网络与 DNS；若用了代理/VPN，确认它没有拦截。"

            detail.contains("refused", ignoreCase = true) ->
                "连接被拒绝。上游端口不可达。"

            else -> "网络层失败，重试即可；若持续出现请检查网络与代理设置。"
        }
        return Diagnosis(Kind.NETWORK, "$name: $detail".ifBlank { "网络异常" }, hint)
    }

    /**
     * 诊断"本地没有可用账号"。
     *
     * @param total 该版本下的账号总数
     * @param cooling 其中仍在冷却的数量
     */
    fun fromNoAccount(total: Int, cooling: Int): Diagnosis {
        val (summary, action) = when {
            total == 0 -> "没有添加任何账号（或都未登录）" to
                "到「账号」页扫码登录，或上传 auth 文件添加账号。"

            cooling >= total -> "所有账号都在冷却中（$cooling/$total）" to
                "等冷却结束。若反复进入冷却，多为被限流；可到「记录」页看失败类型分布。"

            else -> "没有可用账号（$cooling/$total 在冷却）" to
                "检查账号是否已启用，或等冷却结束。"
        }
        return Diagnosis(Kind.NO_ACCOUNT, summary, action)
    }

    /**
     * 把诊断压成一行，写进调用记录。
     *
     * 格式：`[kind] summary ｜ 建议：action`
     * 记录页会原样展示，所以两段都要能让用户看懂。
     */
    fun toRecordLine(diagnosis: Diagnosis): String {
        val base = diagnosis.toRecord()
        return if (diagnosis.action.isBlank()) base else "$base ｜ 建议：${diagnosis.action}"
    }
}
