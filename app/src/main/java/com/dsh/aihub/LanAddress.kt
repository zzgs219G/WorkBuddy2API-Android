package com.dsh.aihub

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 查询本机当前的局域网 IPv4 地址。
 *
 * 用途：把"0.0.0.0:8788"翻译成用户真正要填进客户端的地址。
 *
 * 在此之前，界面只显示 `0.0.0.0:8788（本机与局域网）` —— 这对使用者毫无帮助：
 * 0.0.0.0 是「监听所有网卡」的写法，不是能连的地址；用户必须自己去系统设置里
 * 翻 WLAN 详情才能查到 IP，再手抄进客户端。
 *
 * 实现上的取舍
 * ------------
 *  1) **用 NetworkInterface 枚举，不用 WifiManager**。WifiManager 需要
 *     ACCESS_WIFI_STATE 权限，且只能拿到 Wi-Fi；而本项目也支持 USB 网络共享、
 *     以太网转接等场景，那些不走 Wi-Fi。枚举网卡对三者一视同仁，也不需要权限。
 *  2) **只返回 IPv4**。上游与本项目的监听都是 IPv4（0.0.0.0），给客户端一个
 *     IPv6 地址反而连不上。
 *  3) **优先无线的私有地址**。多网卡设备（同时开 Wi-Fi 与 VPN、或有 Docker
 *     网桥）会枚举出多个地址，选错了用户会一直连不通。排序规则见 [rank]。
 *  4) **拿不到就返回 null，不猜**。界面据此隐藏这一行，而不是显示一个假地址。
 */
object LanAddress {

    /** 一个可用的局域网地址。 */
    data class Entry(
        /** 网卡名，如 wlan0 / eth0。用于在界面上区分多网卡。 */
        val interfaceName: String,
        /** 点分十进制 IPv4。 */
        val address: String,
        /** 是否为私有网段（10./172.16-31./192.168.）。 */
        val isPrivate: Boolean,
    )

    /**
     * 当前所有可用的局域网 IPv4 地址，已按「最可能是用户要用的」排序。
     *
     * 空列表表示既没有 Wi-Fi 也没有以太网（例如只开了移动数据）。
     */
    fun list(): List<Entry> {
        val entries = ArrayList<Entry>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        for (nic in interfaces) {
            // 未启用 / 回环 / 已断开的网卡都不可能出现"同一 WiFi 下的另一台设备"
            val usable = runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in nic.interfaceAddresses) {
                val ip = addr.address
                if (ip !is Inet4Address) continue
                if (ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress) continue
                val host = ip.hostAddress ?: continue
                entries.add(
                    Entry(
                        interfaceName = nic.name.orEmpty(),
                        address = host,
                        isPrivate = isPrivate(host),
                    )
                )
            }
        }
        return entries.sortedBy { rank(it) }
    }

    /** 最可能可用的那一个；没有则 null。 */
    fun primary(): Entry? = list().firstOrNull()

    /**
     * 排序权重，越小越靠前。
     *
     * 依据：手机用户 99% 的场景是「电脑连同一个 Wi-Fi 调手机」，
     * 所以 wlan 网卡 + 私有网段优先级最高。VPN（tun/ppp）与虚拟网桥
     * （docker/br-）虽然也是私有网段，但用户填了就连不通，排最后。
     */
    private fun rank(entry: Entry): Int {
        val name = entry.interfaceName.lowercase()
        val virtual = listOf("tun", "tap", "ppp", "docker", "br-", "veth", "rmnet", "ccmni")
            .any { name.startsWith(it) }
        return when {
            // 移动数据网卡：虽然能出网，但同一 WiFi 下的设备连不进来
            virtual && name.startsWith("rmnet") || name.startsWith("ccmni") -> 40
            virtual -> 30
            !entry.isPrivate -> 20          // 公网地址（少数企业网），能用但不常见
            name.startsWith("wlan") -> 0    // Wi-Fi：最常见
            name.startsWith("ap") -> 0      // 手机作为热点时的网卡
            name.startsWith("eth") -> 1     // 以太网 / USB 网卡
            else -> 10
        }
    }

    /** RFC1918 私有网段判定。 */
    private fun isPrivate(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }

    /**
     * 拼出可直接粘贴给客户端的 baseUrl。
     *
     * @param port 监听端口
     * @param entry 目标网卡；null 时退回 127.0.0.1（仅本机）
     */
    fun baseUrl(port: Int, entry: Entry? = primary()): String =
        "http://${entry?.address ?: "127.0.0.1"}:$port/v1"
}
