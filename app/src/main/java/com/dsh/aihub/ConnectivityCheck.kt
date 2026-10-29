package com.dsh.aihub

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连通性自检 —— 主动向上游发一个最小请求，回答「现在还能不能用」。
 *
 * 为什么需要它
 * ------------
 * 单元测试只能测纯逻辑（分类、清洗、排序），永远碰不到网络，所以回答不了
 * 「上游还认不认我们」这个问题。而这是本类项目最核心的风险：上游在
 * 2026-10-23 起明显收紧了对非官方客户端的识别。
 *
 * 每次失效时，用户手上只有「客户端报错」这一条线索，且不知道该改配置、
 * 该重新登录、还是该等。自检把这三种情况用 [FailureDiagnosis] 区分开。
 *
 * 设计约束（重要）
 * ----------------
 *  1) **只在用户主动点击时执行**。绝不后台自动跑：每次自检都会真实消耗额度，
 *     而且频繁探测本身就可能被上游判定为异常行为。
 *  2) **请求最小化**：max_tokens=1、单轮、一句话。目的是验证链路，不是拿结果。
 *  3) **逐层检查**：本地服务 → 账号 → 上游。任一层失败就停在那里并给出
 *     该层的结论，不把「服务没启动」误报成「上游挂了」。
 *  4) 不抛异常，所有失败都变成可读结论返回 —— 自检本身崩掉是没有意义的。
 */
object ConnectivityCheck {

    /** 一步检查的结果。 */
    data class Step(
        /** 检查项名称，如「本地服务」。 */
        val name: String,
        /** 是否通过。 */
        val ok: Boolean,
        /** 结论文案（通过时说明看到了什么，失败时说明原因）。 */
        val detail: String,
        /** 建议动作；无建议时为空。 */
        val action: String = "",
        /** 失败时的分类；通过时为 null。 */
        val kind: FailureDiagnosis.Kind? = null,
    )

    /** 一次完整自检的结果。 */
    data class Report(val steps: List<Step>) {
        val ok: Boolean get() = steps.isNotEmpty() && steps.all { it.ok && it.kind == null }
        /** 第一个失败步骤，用于界面高亮。 */
        val firstFailure: Step? get() = steps.firstOrNull { !it.ok || it.kind != null }
    }

    /**
     * 执行自检。
     *
     * 按「本地 → 账号 → 上游」顺序，前一层失败就不再往后走 ——
     * 否则本地服务都没启动时还会去请求上游，得到的错误没有诊断价值。
     *
     * @param probeUpstream 是否真的打一次上游。false 时只检查本地与账号，
     *   用于「不想消耗额度」的场景。
     */
    fun run(context: Context, probeUpstream: Boolean = true): Report {
        val steps = ArrayList<Step>()

        // ① 本地服务是否在监听
        val listening = ApiHostService.running
        steps.add(
            if (listening) Step("本地服务", true, "已在监听，端口 ${ApiHostService.PORT}")
            else Step(
                "本地服务", false,
                "服务未启动。客户端此时连不上任何东西。",
                "到「概览」页点「启动服务」。",
            )
        )
        if (!listening) return Report(steps)

        // ② 是否有可用账号
        val account = runCatching { NativeCore.loadAccount(context) }.getOrNull()
        if (account == null) {
            steps.add(
                Step(
                    "账号", false,
                    "没有已登录的账号。",
                    "到「账号」页扫码登录或导入 auth 文件。",
                )
            )
            return Report(steps)
        }
        steps.add(Step("账号", true, "${account.nickname.ifBlank { account.uid.take(8) }}（${account.region.label}）"))

        if (!probeUpstream) return Report(steps)

        // ③ 真的发一次最小请求
        steps.add(probe(context, account))
        return Report(steps)
    }

    /**
     * 向上游发一个最小请求。
     *
     * 复用 [NativeCore.upstreamRequest] 而不是自己拼请求：身份头、会话头、
     * 请求体字段顺序都在那一层统一处理，自检如果绕过去，测出来的就不是
     * 真实调用路径 —— 那样的自检没有意义（真调用挂了自检还绿）。
     */
    private fun probe(context: Context, account: NativeCore.Account): Step {
        val body = JSONObject().apply {
            put("model", "auto")
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            put("max_tokens", 1)
            put("stream", true)
        }
        val started = System.currentTimeMillis()
        return try {
            val upstream = NativeCore.upstreamRequest(context, body, account.region)
            val call = NativeCore.http.newCall(upstream.request)
            call.execute().use { response ->
                val elapsed = System.currentTimeMillis() - started
                if (response.isSuccessful) {
                    Step("上游请求", true, "HTTP 200，耗时 ${elapsed}ms —— 链路正常")
                } else {
                    val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    val diagnosis = FailureDiagnosis.fromHttp(response.code, raw)
                    Step(
                        "上游请求", false,
                        "${diagnosis.summary}（耗时 ${elapsed}ms）",
                        diagnosis.action,
                        diagnosis.kind,
                    )
                }
            }
        } catch (e: Exception) {
            // 网络层失败：没拿到任何 HTTP 响应
            val diagnosis = FailureDiagnosis.fromNetwork(e)
            Step("上游请求", false, diagnosis.summary, diagnosis.action, diagnosis.kind)
        }
    }
}
