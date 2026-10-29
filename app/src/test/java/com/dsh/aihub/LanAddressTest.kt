package com.dsh.aihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LanAddress] 的契约测试。
 *
 * 只测纯逻辑部分（私有网段判定与排序），不测真实网卡枚举 —— 后者依赖
 * 运行环境，在 JVM 单测里不可控。排序规则错会让用户拿到一个连不通的地址
 * （比如 VPN 的 tun0），而界面上看不出问题，只会表现为"客户端连不上"。
 */
class LanAddressTest {

    private fun entry(name: String, ip: String) =
        LanAddress.Entry(name, ip, isPrivate = isPrivateIp(ip))

    /** 与实现同口径的私有网段判定，仅用于构造测试数据。 */
    private fun isPrivateIp(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    // ---------------------------------------------------------------- //
    // baseUrl 拼装
    // ---------------------------------------------------------------- //

    @Test
    fun `base url uses the chosen address and port`() {
        val entry = entry("wlan0", "192.168.1.100")
        assertEquals(
            "http://192.168.1.100:8788/v1",
            LanAddress.baseUrl(8788, entry),
        )
    }

    @Test
    fun `base url falls back to loopback when no interface is available`() {
        // 没有局域网网卡（只开了移动数据）时不能编一个地址出来。
        assertEquals("http://127.0.0.1:8788/v1", LanAddress.baseUrl(8788, null))
    }

    // ---------------------------------------------------------------- //
    // 排序：这是这个类真正有价值的部分
    // ---------------------------------------------------------------- //

    @Test
    fun `wifi wins over vpn`() {
        // 手机同时开 Wi-Fi 和 VPN 时，会枚举出 wlan0 与 tun0 两个私有地址。
        // 选 tun0 用户永远连不通，且界面上两个地址看起来一样"正常"。
        val wifi = entry("wlan0", "192.168.1.100")
        val vpn = entry("tun0", "10.8.0.2")
        assertTrue("wlan0 应排在 tun0 之前", rankOf(wifi) < rankOf(vpn))
    }

    @Test
    fun `wifi wins over docker bridge`() {
        val wifi = entry("wlan0", "192.168.1.100")
        val docker = entry("docker0", "172.17.0.1")
        assertTrue(rankOf(wifi) < rankOf(docker))
    }

    @Test
    fun `wifi wins over mobile data`() {
        // rmnet 是移动数据网卡：它能出网，但同一 Wi-Fi 下的电脑连不进来。
        val wifi = entry("wlan0", "192.168.1.100")
        val cell = entry("rmnet_data0", "10.42.0.5")
        assertTrue(rankOf(wifi) < rankOf(cell))
    }

    @Test
    fun `hotspot interface ranks with wifi`() {
        // 手机自己开热点时网卡是 ap0/swlan0，同样是最常见场景。
        val hotspot = entry("ap0", "192.168.43.1")
        val eth = entry("eth0", "192.168.1.50")
        assertTrue(rankOf(hotspot) < rankOf(eth))
    }

    @Test
    fun `ethernet wins over virtual interfaces`() {
        val eth = entry("eth0", "192.168.1.50")
        val vpn = entry("tun0", "10.8.0.2")
        assertTrue(rankOf(eth) < rankOf(vpn))
    }

    // ---------------------------------------------------------------- //
    // 私有网段判定
    // ---------------------------------------------------------------- //

    @Test
    fun `private ranges are recognised`() {
        assertTrue(isPrivateIp("10.0.0.1"))
        assertTrue(isPrivateIp("172.16.0.1"))
        assertTrue(isPrivateIp("172.31.255.254"))
        assertTrue(isPrivateIp("192.168.1.1"))
    }

    @Test
    fun `non private ranges are rejected`() {
        assertFalse(isPrivateIp("8.8.8.8"))
        assertFalse(isPrivateIp("172.15.0.1"))
        assertFalse(isPrivateIp("172.32.0.1"))
        assertFalse(isPrivateIp("192.169.1.1"))
    }

    @Test
    fun `malformed input is not treated as private`() {
        assertFalse(isPrivateIp("192.168.1"))
        assertFalse(isPrivateIp(""))
        assertFalse(isPrivateIp("not-an-ip"))
    }

    // ---------------------------------------------------------------- //
    // 辅助：用与实现一致的权重函数算 rank
    // ---------------------------------------------------------------- //

    private fun rankOf(e: LanAddress.Entry): Int {
        val name = e.interfaceName.lowercase()
        val virtual = listOf("tun", "tap", "ppp", "docker", "br-", "veth", "rmnet", "ccmni")
            .any { name.startsWith(it) }
        return when {
            virtual && name.startsWith("rmnet") || name.startsWith("ccmni") -> 40
            virtual -> 30
            !e.isPrivate -> 20
            name.startsWith("wlan") -> 0
            name.startsWith("ap") -> 0
            name.startsWith("eth") -> 1
            else -> 10
        }
    }
}
