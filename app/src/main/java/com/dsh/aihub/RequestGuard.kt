package com.dsh.aihub

/**
 * 入站请求的可信性检查（Host / Origin）。
 *
 * 为什么必须有这个
 * ----------------
 * 「只绑 127.0.0.1」不等于安全边界。本机任何一个进程都能连这个端口；
 * 开了局域网之后，同网段任何人都能连。真正危险的是**浏览器**：
 *
 *   1) **DNS rebinding**：恶意网页把自己的域名解析到 127.0.0.1，
 *      浏览器于是允许该页面 fetch `http://evil.example:8788/v1/...`
 *      —— 页面自己不会觉得这算跨域。请求带了 Authorization 就能用掉你的额度，
 *      甚至读走响应内容。
 *   2) **恶意网页直连**：页面直接 fetch `http://127.0.0.1:8788`，同理。
 *
 * 浏览器发起的请求一定带 `Origin`，且一定带 `Host`。这两条给了我们不依赖
 * 认证密钥的判据：
 *
 *   · `Host` 必须是**本服务确实在监听的名字**（回环名，或本机某个网卡地址）。
 *     收到 `Host: evil.example:8788` 说明请求虽然到了本机，但客户端以为自己在
 *     访问 evil.example —— 这正是 rebinding 的特征。
 *   · `Origin` 若存在，必须是本机来源。非浏览器客户端（curl / OpenAI SDK /
 *     Cherry Studio / Claude Code）根本不发 Origin，所以这条不会误伤它们。
 *
 * 设计取舍（重要）
 * ----------------
 *  1) **Origin 缺失一律放行**。只有浏览器会发它；强制要求等于把所有 CLI 客户端
 *     打死。这是本项目最主要的调用方式，不能碰。
 *  2) **Host 缺失一律放行**。HTTP/1.0 与部分精简客户端不发 Host。这类客户端
 *     不会构造 rebinding 攻击（它们本来就跑在本机）。
 *  3) **只做判断，不记录、不发请求**——纯函数，可单测。
 *  4) 与 WorkBuddy-Hub 的 BridgeServer.hostIsAllowed/originIsAllowed 同思路，
 *     但本项目的监听地址是运行时可变的（局域网开关），因此把「本机地址集合」
 *     作为参数传入，而不是写死一组常量。
 */
object RequestGuard {

    /** 检查结论。拒绝时带上可读原因，直接回给客户端，便于排查。 */
    sealed class Verdict {
        object Allow : Verdict()

        /** @param reason 回给客户端的原因（不含敏感信息） */
        data class Deny(val reason: String) : Verdict()
    }

    /** 回环访问时允许的 Host 名（不含端口）。 */
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /**
     * 校验 Host。
     *
     * @param hostHeader 原始 `Host` 头（可能含端口），空串表示客户端没发
     * @param localAddresses 本机当前的 IPv4 地址列表（[LanAddress.list] 的 address 集合）
     */
    fun checkHost(hostHeader: String, localAddresses: Collection<String>): Verdict {
        if (hostHeader.isBlank()) return Verdict.Allow
        val host = stripPort(hostHeader).lowercase()

        if (host in LOOPBACK_HOSTS) return Verdict.Allow
        // 局域网访问：客户端会用手机的实际 IP 作为 Host，那是合法的
        if (localAddresses.any { it.lowercase() == host }) return Verdict.Allow
        // 0.0.0.0 与 * 是监听写法，不是客户端会发的 Host；
        // 但仍放行，因为个别代理会原样转发 —— 拒绝它收益为零、误伤风险有。
        if (host == "0.0.0.0" || host == "*") return Verdict.Allow

        return Verdict.Deny(
            "Host 头不被接受（$host）。本服务只应答本机地址；" +
                "若你确实在另一台设备上调用，请把 baseUrl 填成手机的实际局域网 IP。"
        )
    }

    /**
     * 校验 Origin。
     *
     * @param originHeader 原始 `Origin` 头，空串表示没有（绝大多数 CLI 客户端）
     * @param localAddresses 本机 IPv4 地址列表
     */
    fun checkOrigin(originHeader: String, localAddresses: Collection<String>): Verdict {
        // 非浏览器客户端不发 Origin —— 必须放行，否则 curl / SDK 全部失效。
        if (originHeader.isBlank()) return Verdict.Allow
        // file:// 页面的 Origin 是字符串 "null"
        if (originHeader == "null") {
            return Verdict.Deny("拒绝来自本地文件页面的请求（Origin: null）。")
        }

        // Origin 形如 scheme://host[:port]
        val withoutScheme = originHeader.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return Verdict.Deny("Origin 头格式无法解析：$originHeader")
        val host = stripPort(withoutScheme.substringBefore('/')).lowercase()

        if (host in LOOPBACK_HOSTS) return Verdict.Allow
        if (localAddresses.any { it.lowercase() == host }) return Verdict.Allow

        return Verdict.Deny(
            "拒绝跨站请求（Origin: $originHeader）。" +
                "本接口只接受本机或局域网地址发起的调用；" +
                "浏览器页面不能直接调用它，请用命令行或客户端程序。"
        )
    }

    /**
     * 依次检查 Host 与 Origin，任一失败即拒绝。
     *
     * @param headers 已 lower-case key 的请求头
     */
    fun check(headers: Map<String, String>, localAddresses: Collection<String>): Verdict {
        val host = checkHost(headers["host"].orEmpty(), localAddresses)
        if (host is Verdict.Deny) return host
        return checkOrigin(headers["origin"].orEmpty(), localAddresses)
    }

    /** 去掉 `:port` 后缀；IPv6 的 `[::1]:8080` 也按同一规则处理。 */
    private fun stripPort(value: String): String {
        val trimmed = value.trim()
        // 方括号形式：取到右方括号为止
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            return if (end > 0) trimmed.substring(0, end + 1) else trimmed
        }
        // 普通 host:port（只有一个冒号才当作端口分隔）
        val colon = trimmed.lastIndexOf(':')
        return if (colon > 0 && trimmed.indexOf(':') == colon) trimmed.substring(0, colon) else trimmed
    }
}
