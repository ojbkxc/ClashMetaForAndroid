package com.github.kr328.clash.service.root

import com.github.kr328.clash.common.RootChecker

object RootHelper {
    private const val TPROXY_PORT = 7892
    private const val DNS_HIJACK_PORT = 1053
    private const val PACKAGE_NAME = "com.github.kr328.clash"

    // Chain names - 统一命名风格（参考 Surfing）
    private const val CHAIN_EXTERNAL = "CLASH_EXTERNAL"
    private const val CHAIN_LOCAL = "CLASH_LOCAL"
    private const val CHAIN_LOCK_BG = "CLASH_LOCK_BG"
    private const val CHAIN_DNS_EXTERNAL = "CLASH_DNS_EXTERNAL"
    private const val CHAIN_DNS_LOCAL = "CLASH_DNS_LOCAL"
    private const val CHAIN_DIVERT = "CLASH_DIVERT"
    private const val CHAIN_BYPASS = "CLASH_BYPASS"
    private const val CHAIN_LOCAL_IP = "CLASH_LOCAL_IP"
    private const val CHAIN_LOCAL_IP_V6 = "CLASH_LOCAL_IP_V6"

    // 路由标记和表ID（参考 Surfing）
    private const val MARK_ID = "0x1/0x1"
    private const val MARK_VALUE = "0x1"
    private const val TABLE_ID = "100"
    private const val TABLE_PREF = "100"

    // DNS劫持模式：true=使用nat表REDIRECT，false=使用mangle表TPROXY
    private var useNatTableForDns = true

    // 缓存动态获取的 UID
    private var cachedProxyUid: Int = -1

    /**
     * 初始化应用 UID（由 Activity 调用，传入 applicationInfo.uid）
     */
    fun initAppUid(uid: Int) {
        cachedProxyUid = uid
    }

    /**
     * 检查 root 权限是否可用
     */
    fun isRootAvailable(): Boolean {
        return RootChecker.isRooted() && RootChecker.requestRoot()
    }

    /**
     * 重新请求 root 权限（带重试）
     */
    fun requestRootWithRetry(): Boolean {
        return RootChecker.requestRootWithRetry()
    }

    /**
     * 设置 DNS 劫持模式
     * @param useNatTable true=使用nat表REDIRECT，false=使用mangle表TPROXY
     */
    fun setDnsHijackMode(useNatTable: Boolean) {
        useNatTableForDns = useNatTable
    }

    /**
     * 获取应用 UID
     */
    private fun getAppUid(): Int {
        if (cachedProxyUid > 0) return cachedProxyUid
        val (code, output) = RootChecker.execute("dumpsys package $PACKAGE_NAME")
        if (code == 0) {
            val match = Regex("userId=(\\d+)").find(output)
            if (match != null) {
                cachedProxyUid = match.groupValues[1].toInt()
                return cachedProxyUid
            }
        }
        return -1
    }

    /**
     * 获取本地 IPv4 地址列表
     */
    private fun getLocalIpv4Addresses(): List<String> {
        val (code, output) = RootChecker.execute("ip -4 a | grep inet | awk '{print \$2}' | grep -vE '^127.0.0.1'")
        if (code != 0) return emptyList()
        return output.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("::") }
    }

    /**
     * 获取本地 IPv6 地址列表
     */
    private fun getLocalIpv6Addresses(): List<String> {
        val (code, output) = RootChecker.execute("ip -6 a | grep inet6 | awk '{print \$2}' | grep -vE '^fe80|^::1|^::/'")
        if (code != 0) return emptyList()
        return output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 创建 iptables chain，如果已存在则先清理再创建
     */
    private fun ensureChain(table: String, chain: String): Boolean {
        RootChecker.execute("iptables -t $table -D OUTPUT -j $chain 2>/dev/null")
        RootChecker.execute("iptables -t $table -D PREROUTING -j $chain 2>/dev/null")
        RootChecker.execute("iptables -t $table -F $chain 2>/dev/null")
        RootChecker.execute("iptables -t $table -X $chain 2>/dev/null")
        val (code, _) = RootChecker.execute("iptables -t $table -N $chain")
        return code == 0
    }

    /**
     * 检测内核是否支持 TPROXY
     */
    private fun isTProxySupported(): Boolean {
        RootChecker.execute("iptables -t mangle -F CLASH_TPROXY_TEST 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X CLASH_TPROXY_TEST 2>/dev/null")
        val (c1, _) = RootChecker.execute("iptables -t mangle -N CLASH_TPROXY_TEST")
        if (c1 != 0) return false
        val (c2, _) = RootChecker.execute("iptables -t mangle -A CLASH_TPROXY_TEST -p tcp -j TPROXY --tproxy-mark 0x1/0x1 --on-port 1")
        val (c3, _) = RootChecker.execute("iptables -t mangle -A CLASH_TPROXY_TEST -p udp -j TPROXY --tproxy-mark 0x1/0x1 --on-port 1")
        RootChecker.execute("iptables -t mangle -F CLASH_TPROXY_TEST 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X CLASH_TPROXY_TEST 2>/dev/null")
        return c2 == 0 && c3 == 0
    }

    /**
     * 优化内核参数（参考 Surfing）
     */
    private fun optimizeKernel(): Boolean {
        val commands = listOf(
            // UDP conntrack 超时优化
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout=30 2>/dev/null",
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout_stream=15 2>/dev/null",
            // 直接写入 proc 文件（某些设备 sysctl 被禁用）
            "echo 30 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout 2>/dev/null",
            "echo 15 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout_stream 2>/dev/null",
            // TCP conntrack 优化
            "sysctl -w net.netfilter.nf_conntrack_tcp_timeout_established=3600 2>/dev/null",
            "sysctl -w net.ipv4.tcp_tw_reuse=1 2>/dev/null",
            // IP forward
            "sysctl -w net.ipv4.ip_forward=1 2>/dev/null"
        )
        for (cmd in commands) {
            RootChecker.execute(cmd)
        }
        return true
    }

    /**
     * Apply transparent proxy rules
     * 参考 Surfing 的完整 TPROXY 方案
     */
    suspend fun applyTransparentProxy(): Pair<Boolean, String> {
        clearTransparentProxy()

        val proxyUid = getAppUid()
        if (proxyUid < 0) {
            return Pair(false, "无法获取应用 UID")
        }

        optimizeKernel()

        if (isTProxySupported()) {
            val result = applyTProxy(proxyUid)
            if (result.first) {
                return Pair(true, "TPROXY 模式（支持 TCP+UDP）")
            }
            clearTransparentProxy()
        }

        val result = applyRedirect(proxyUid)
        if (result.first) {
            return Pair(true, "REDIRECT 模式（仅支持 TCP）")
        }
        return result
    }

    /**
     * TPROXY 模式 - 参考 Surfing 完整实现
     */
    private fun applyTProxy(proxyUid: Int): Pair<Boolean, String> {
        val proxyUidStr = proxyUid.toString()

        // ========== 1. 设置路由规则（IPv4） ==========
        RootChecker.execute("ip rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip route del local default dev lo table $TABLE_ID 2>/dev/null")
        var (code, output) = RootChecker.execute("ip rule add fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF")
        if (code != 0) return Pair(false, "设置路由规则失败: $output")
        (code, output) = RootChecker.execute("ip route add local default dev lo table $TABLE_ID")
        if (code != 0) return Pair(false, "设置本地路由表失败: $output")

        // ========== 2. 创建 CLASH_LOCAL_IP 链（IPv4本地地址） ==========
        if (!ensureChain("mangle", CHAIN_LOCAL_IP)) {
            return Pair(false, "创建 LOCAL_IP 链失败")
        }
        val localIpv4List = getLocalIpv4Addresses()
        for (ip in localIpv4List) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL_IP -d $ip -j RETURN")
        }

        // ========== 3. 创建 CLASH_LOCAL_IP_V6 链（IPv6本地地址） ==========
        if (!ensureChain("mangle", CHAIN_LOCAL_IP_V6)) {
            // IPv6 可能不支持，继续
        } else {
            val localIpv6List = getLocalIpv6Addresses()
            for (ip in localIpv6List) {
                RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_IP_V6 -d $ip -j RETURN 2>/dev/null")
            }
        }

        // ========== 4. 创建 BOX_EXTERNAL 链（PREROUTING） ==========
        if (!ensureChain("mangle", CHAIN_EXTERNAL)) {
            return Pair(false, "创建 BOX_EXTERNAL 链失败")
        }

        // 第1-2位：跳过 DNS 53 端口（UDP 和 TCP）
        RootChecker.execute("iptables -t mangle -I $CHAIN_EXTERNAL 1 -p udp --dport 53 -j RETURN")
        RootChecker.execute("iptables -t mangle -I $CHAIN_EXTERNAL 2 -p tcp --dport 53 -j RETURN")

        // 跳过局域网流量（参考 Surfing）
        val bypassSubnets = listOf(
            "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4"
        )
        for (subnet in bypassSubnets) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -d $subnet -j RETURN")
        }

        // 处理本地 IP 地址
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -j $CHAIN_LOCAL_IP")

        // TPROXY 规则 - 设置在 lo 接口上（参考 Surfing）
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p tcp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p udp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")

        // 添加到 PREROUTING 链
        RootChecker.execute("iptables -t mangle -I PREROUTING -j $CHAIN_EXTERNAL")

        // ========== 5. 创建 BOX_LOCAL 链（OUTPUT） ==========
        if (!ensureChain("mangle", CHAIN_LOCAL)) {
            return Pair(false, "创建 BOX_LOCAL 链失败")
        }

        // 第1位：跳过代理进程自身
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 1 -m owner --uid-owner $proxyUidStr -j RETURN")

        // 第2位：CONNMARK 恢复标记
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 2 -j CONNMARK --restore-mark")

        // 第3位：跳过已标记的连接
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 3 -m mark --mark $MARK_ID -j ACCEPT")

        // 第4-5位：跳过 DNS 53 端口
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 4 -p udp --dport 53 -j RETURN")
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 5 -p tcp --dport 53 -j RETURN")

        // 跳过局域网流量
        for (subnet in bypassSubnets) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -d $subnet -j RETURN")
        }

        // 处理本地 IP 地址
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -j $CHAIN_LOCAL_IP")

        // 设置标记（TCP 和 UDP）
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -p tcp -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -p udp -j MARK --set-xmark $MARK_ID")

        // 末尾：CONNMARK 保存标记
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -j CONNMARK --save-mark")

        // 添加到 OUTPUT 链
        RootChecker.execute("iptables -t mangle -I OUTPUT -j $CHAIN_LOCAL")

        // ========== 6. DNS 劫持（参考 Surfing） ==========
        if (useNatTableForDns) {
            // 使用 nat 表 REDIRECT 模式
            if (!applyDnsHijackNatMode(proxyUidStr)) {
                return Pair(false, "DNS 劫持失败")
            }
        } else {
            // 使用 mangle 表 TPROXY 模式
            if (!applyDnsHijackMangleMode(proxyUidStr, TPROXY_PORT)) {
                return Pair(false, "DNS 劫持失败")
            }
        }

        // ========== 7. 创建 DIVERT 链加速已建立连接 ==========
        if (!ensureChain("mangle", CHAIN_DIVERT)) {
            // DIVERT 非必需，继续执行
        } else {
            RootChecker.execute("iptables -t mangle -A $CHAIN_DIVERT -j MARK --set-xmark $MARK_ID")
            RootChecker.execute("iptables -t mangle -A $CHAIN_DIVERT -j ACCEPT")
            RootChecker.execute("iptables -t mangle -I PREROUTING -p tcp -m socket -j $CHAIN_DIVERT")
        }

        return Pair(true, "")
    }

    /**
     * DNS 劫持 - nat 表 REDIRECT 模式（参考 Surfing）
     */
    private fun applyDnsHijackNatMode(proxyUidStr: String): Boolean {
        // CLASH_DNS_EXTERNAL（PREROUTING）
        if (!ensureChain("nat", CHAIN_DNS_EXTERNAL)) {
            return false
        }
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_EXTERNAL -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        RootChecker.execute("iptables -t nat -I PREROUTING -j $CHAIN_DNS_EXTERNAL")

        // CLASH_DNS_LOCAL（OUTPUT）
        if (!ensureChain("nat", CHAIN_DNS_LOCAL)) {
            return false
        }
        if (proxyUidStr.isNotEmpty()) {
            RootChecker.execute("iptables -t nat -I $CHAIN_DNS_LOCAL 1 -m owner --uid-owner $proxyUidStr -j RETURN")
        }
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_LOCAL -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        RootChecker.execute("iptables -t nat -I OUTPUT -j $CHAIN_DNS_LOCAL")

        return true
    }

    /**
     * DNS 劫持 - mangle 表 TPROXY 模式（参考 Surfing）
     * 用于 use_nat_table=false 的情况
     */
    private fun applyDnsHijackMangleMode(proxyUidStr: String, dnsPort: Int): Boolean {
        // CLASH_DNS_EXTERNAL（PREROUTING）
        if (!ensureChain("mangle", CHAIN_DNS_EXTERNAL)) {
            return false
        }
        RootChecker.execute("iptables -t mangle -A $CHAIN_DNS_EXTERNAL -p udp --dport 53 -j TPROXY --tproxy-mark $MARK_ID --on-port $dnsPort")
        RootChecker.execute("iptables -t mangle -I PREROUTING -j $CHAIN_DNS_EXTERNAL")

        // CLASH_DNS_LOCAL（OUTPUT）
        if (!ensureChain("mangle", CHAIN_DNS_LOCAL)) {
            return false
        }
        if (proxyUidStr.isNotEmpty()) {
            RootChecker.execute("iptables -t mangle -I $CHAIN_DNS_LOCAL 1 -m owner --uid-owner $proxyUidStr -j RETURN")
        }
        RootChecker.execute("iptables -t mangle -A $CHAIN_DNS_LOCAL -p udp --dport 53 -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -I OUTPUT -j $CHAIN_DNS_LOCAL")

        return true
    }

    /**
     * REDIRECT 模式 - 仅支持 TCP
     */
    private fun applyRedirect(proxyUid: Int): Pair<Boolean, String> {
        if (!ensureChain("nat", CHAIN_EXTERNAL)) {
            return Pair(false, "创建 chain 失败")
        }

        val steps = listOf(
            "iptables -t nat -A $CHAIN_EXTERNAL -m owner --uid-owner $proxyUid -j RETURN",
            "iptables -t nat -A $CHAIN_EXTERNAL -d 127.0.0.0/8 -j RETURN",
            "iptables -t nat -A $CHAIN_EXTERNAL -d 10.0.0.0/8 -j RETURN",
            "iptables -t nat -A $CHAIN_EXTERNAL -d 172.16.0.0/12 -j RETURN",
            "iptables -t nat -A $CHAIN_EXTERNAL -d 192.168.0.0/16 -j RETURN",
            "iptables -t nat -A $CHAIN_EXTERNAL -p tcp -j REDIRECT --to-ports $TPROXY_PORT",
            "iptables -t nat -I OUTPUT -j $CHAIN_EXTERNAL",
        )
        for (cmd in steps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0) {
                clearTransparentProxy()
                return Pair(false, "REDIRECT 执行失败: $output")
            }
        }
        return Pair(true, "")
    }

    /**
     * 清除透明代理规则 - 完整清理（参考 Surfing stop_tproxy）
     */
    private fun clearTransparentProxy() {
        // 清理 IPv4 路由规则
        RootChecker.execute("ip rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip route del local default dev lo table $TABLE_ID 2>/dev/null")

        // 清理 DIVERT 链
        RootChecker.execute("iptables -t mangle -D PREROUTING -p tcp -m socket -j $CHAIN_DIVERT 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_DIVERT 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_DIVERT 2>/dev/null")

        // 清理 DNS 链（mangle 和 nat 表）
        for (table in listOf("nat", "mangle")) {
            RootChecker.execute("iptables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_LOCAL 2>/dev/null")
        }

        // 清理 BOX_LOCAL 和 BOX_EXTERNAL
        RootChecker.execute("iptables -t mangle -D PREROUTING -j $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -D OUTPUT -j $CHAIN_LOCAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_LOCAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_LOCAL 2>/dev/null")

        // 清理 nat 表
        RootChecker.execute("iptables -t nat -D OUTPUT -j $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t nat -F $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t nat -X $CHAIN_EXTERNAL 2>/dev/null")

        // 清理 LOCAL_IP 链
        RootChecker.execute("iptables -t mangle -F $CHAIN_LOCAL_IP 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_LOCAL_IP 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_LOCAL_IP_V6 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_LOCAL_IP_V6 2>/dev/null")
    }

    /**
     * Apply lock background rules
     */
    suspend fun applyLockBackground(): Pair<Boolean, String> {
        clearLockBackground()

        if (!ensureChain("mangle", CHAIN_LOCK_BG)) {
            return Pair(false, "创建锁定 chain 失败")
        }

        val steps = listOf(
            "iptables -t mangle -A $CHAIN_LOCK_BG -j CONNMARK --restore-mark",
            "iptables -t mangle -A $CHAIN_LOCK_BG -m mark ! --mark 0 -j RETURN",
            "iptables -t mangle -A $CHAIN_LOCK_BG -j MARK --set-xmark $MARK_ID",
            "iptables -t mangle -A $CHAIN_LOCK_BG -j CONNMARK --save-mark",
            "iptables -t mangle -I OUTPUT -j $CHAIN_LOCK_BG",
        )
        for (cmd in steps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0) {
                clearLockBackground()
                return Pair(false, "锁定后台执行失败: $output")
            }
        }
        return Pair(true, "")
    }

    /**
     * 清除锁定后台规则
     */
    private fun clearLockBackground() {
        RootChecker.execute("iptables -t mangle -D OUTPUT -j $CHAIN_LOCK_BG 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_LOCK_BG 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_LOCK_BG 2>/dev/null")
    }

    /**
     * Apply DNS hijack rules（独立调用）
     */
    suspend fun applyDnsHijack(): Pair<Boolean, String> {
        clearDnsHijack()

        val proxyUid = getAppUid()
        val proxyUidStr = if (proxyUid > 0) proxyUid.toString() else ""

        if (useNatTableForDns) {
            if (!applyDnsHijackNatMode(proxyUidStr)) {
                return Pair(false, "创建 DNS 劫持链失败")
            }
        } else {
            if (!applyDnsHijackMangleMode(proxyUidStr, DNS_HIJACK_PORT)) {
                return Pair(false, "创建 DNS 劫持链失败")
            }
        }

        return Pair(true, "")
    }

    /**
     * 清除 DNS 劫持规则
     */
    private fun clearDnsHijack() {
        for (table in listOf("nat", "mangle")) {
            RootChecker.execute("iptables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_LOCAL 2>/dev/null")
        }
    }

    /**
     * 单独清除透明代理规则
     */
    suspend fun clearTransparentProxyRules() {
        clearTransparentProxy()
    }

    /**
     * 单独清除锁定后台规则
     */
    suspend fun clearLockBackgroundRules() {
        clearLockBackground()
    }

    /**
     * 单独清除 DNS 劫持规则
     */
    suspend fun clearDnsHijackRules() {
        clearDnsHijack()
    }

    /**
     * Clear all rules
     */
    suspend fun clearAllRules() {
        clearTransparentProxy()
        clearLockBackground()
        clearDnsHijack()
    }
}
