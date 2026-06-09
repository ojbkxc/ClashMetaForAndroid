package com.github.kr328.clash.service.root

import com.github.kr328.clash.common.RootChecker

object RootHelper {
    private const val TPROXY_PORT = 7892
    private const val DNS_HIJACK_PORT = 1053
    private const val PACKAGE_NAME = "com.github.kr328.clash"

    // Chain names
    private const val CHAIN_META = "CLASH_META"
    private const val CHAIN_LOCK_BG = "CLASH_LOCK_BG"
    private const val CHAIN_DNS_HIJACK = "CLASH_DNS_HIJACK"

    // 记录当前使用的代理模式，用于清理
    private var currentProxyMode: String = "none"

    // 缓存动态获取的 UID
    private var cachedProxyUid: Int = -1

    /**
     * 初始化应用 UID（由 Activity 调用，传入 applicationInfo.uid）
     * 这是最可靠的方式，比 shell 命令获取更稳定
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
     * 获取应用 UID
     * 优先使用 initAppUid() 设置的值，回退到 shell 命令获取
     */
    private fun getAppUid(): Int {
        if (cachedProxyUid > 0) return cachedProxyUid
        // 回退：通过 shell 命令获取
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
     * 创建 iptables chain，如果已存在则先清理再创建
     * 避免 "Chain already exists" 错误
     */
    private fun ensureChain(table: String, chain: String): Boolean {
        // 先尝试从 OUTPUT 链断开、清空、删除（忽略错误）
        RootChecker.execute("iptables -t $table -D OUTPUT -j $chain 2>/dev/null")
        RootChecker.execute("iptables -t $table -F $chain 2>/dev/null")
        RootChecker.execute("iptables -t $table -X $chain 2>/dev/null")
        // 创建新 chain
        val (code, _) = RootChecker.execute("iptables -t $table -N $chain")
        return code == 0
    }

    /**
     * 检测内核是否支持 TPROXY
     * 用分号分隔确保清理命令一定执行，逐条检查关键步骤
     */
    private fun isTProxySupported(): Boolean {
        // 先清理可能残留的测试 chain
        RootChecker.execute("iptables -t mangle -F CLASH_TPROXY_TEST 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X CLASH_TPROXY_TEST 2>/dev/null")
        // 创建测试 chain
        val (c1, _) = RootChecker.execute("iptables -t mangle -N CLASH_TPROXY_TEST")
        if (c1 != 0) return false
        // 尝试添加 TPROXY 规则
        val (c2, _) = RootChecker.execute("iptables -t mangle -A CLASH_TPROXY_TEST -p tcp -j TPROXY --tproxy-mark 0x1/0x1 --on-port 1")
        // 清理测试 chain
        RootChecker.execute("iptables -t mangle -F CLASH_TPROXY_TEST 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X CLASH_TPROXY_TEST 2>/dev/null")
        return c2 == 0
    }

    /**
     * Apply transparent proxy iptables rules.
     * 优先使用 TPROXY 模式（支持 TCP+UDP），不支持时回退到 REDIRECT 模式（仅 TCP）
     * 参考 SagerNet 的 transproxy_mode 设计
     * @return Pair(success, errorMessage)
     */
    suspend fun applyTransparentProxy(): Pair<Boolean, String> {
        clearTransparentProxy()

        val proxyUid = getAppUid()
        if (proxyUid < 0) {
            return Pair(false, "无法获取应用 UID，请确认应用已正确安装")
        }

        // 检测 TPROXY 支持
        if (isTProxySupported()) {
            val result = applyTProxy(proxyUid)
            if (result.first) {
                currentProxyMode = "tproxy"
                return Pair(true, "")
            }
            clearTransparentProxy()
        }

        // 回退到 REDIRECT 模式（仅 TCP，兼容所有设备）
        val result = applyRedirect(proxyUid)
        if (result.first) {
            currentProxyMode = "redirect"
            return Pair(true, "REDIRECT 模式（仅支持 TCP，UDP 不可用）")
        }
        return result
    }

    /**
     * TPROXY 模式：支持 TCP + UDP，需要内核支持
     */
    private fun applyTProxy(proxyUid: Int): Pair<Boolean, String> {
        // 使用 ensureChain 避免 "Chain already exists"
        if (!ensureChain("mangle", CHAIN_META)) {
            return Pair(false, "TPROXY 创建 chain 失败")
        }

        data class Step(val cmd: String, val desc: String)
        val steps = listOf(
            // 跳过代理进程自身流量
            Step("iptables -t mangle -A $CHAIN_META -m owner --uid-owner $proxyUid -j RETURN", "跳过代理进程流量"),
            // 跳过局域网流量（参考 SagerNet 的 bypassLan 方案）
            Step("iptables -t mangle -A $CHAIN_META -d 127.0.0.0/8 -j RETURN", "跳过本地回环"),
            Step("iptables -t mangle -A $CHAIN_META -d 10.0.0.0/8 -j RETURN", "跳过局域网 10.x"),
            Step("iptables -t mangle -A $CHAIN_META -d 172.16.0.0/12 -j RETURN", "跳过局域网 172.16.x"),
            Step("iptables -t mangle -A $CHAIN_META -d 192.168.0.0/16 -j RETURN", "跳过局域网 192.168.x"),
            Step("iptables -t mangle -A $CHAIN_META -d 224.0.0.0/4 -j RETURN", "跳过组播地址"),
            Step("iptables -t mangle -A $CHAIN_META -d 240.0.0.0/4 -j RETURN", "跳过保留地址"),
            // TPROXY 规则
            Step("iptables -t mangle -A $CHAIN_META -p tcp -j TPROXY --tproxy-mark 0x1/0x1 --on-port $TPROXY_PORT", "设置 TCP TPROXY"),
            Step("iptables -t mangle -A $CHAIN_META -p udp -j TPROXY --tproxy-mark 0x1/0x1 --on-port $TPROXY_PORT", "设置 UDP TPROXY"),
            // 应用到 OUTPUT 链
            Step("iptables -t mangle -I OUTPUT -j $CHAIN_META", "应用 chain 到 OUTPUT"),
            // 路由规则
            Step("ip rule add fwmark 1 table 100", "添加路由规则"),
            Step("ip route add local 0.0.0.0/0 dev lo table 100", "添加本地路由"),
        )
        for ((cmd, desc) in steps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0) return Pair(false, "TPROXY $desc 失败: $output")
        }
        return Pair(true, "")
    }

    /**
     * REDIRECT 模式：仅支持 TCP，兼容所有 Android 设备
     */
    private fun applyRedirect(proxyUid: Int): Pair<Boolean, String> {
        if (!ensureChain("nat", CHAIN_META)) {
            return Pair(false, "REDIRECT 创建 chain 失败")
        }

        data class Step(val cmd: String, val desc: String)
        val steps = listOf(
            Step("iptables -t nat -A $CHAIN_META -m owner --uid-owner $proxyUid -j RETURN", "跳过代理进程流量"),
            Step("iptables -t nat -A $CHAIN_META -d 127.0.0.0/8 -j RETURN", "跳过本地回环"),
            Step("iptables -t nat -A $CHAIN_META -d 10.0.0.0/8 -j RETURN", "跳过局域网 10.x"),
            Step("iptables -t nat -A $CHAIN_META -d 172.16.0.0/12 -j RETURN", "跳过局域网 172.16.x"),
            Step("iptables -t nat -A $CHAIN_META -d 192.168.0.0/16 -j RETURN", "跳过局域网 192.168.x"),
            Step("iptables -t nat -A $CHAIN_META -p tcp -j REDIRECT --to-ports $TPROXY_PORT", "设置 TCP REDIRECT"),
            Step("iptables -t nat -I OUTPUT -j $CHAIN_META", "应用 chain 到 OUTPUT"),
        )
        for ((cmd, desc) in steps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0) {
                clearTransparentProxy()
                return Pair(false, "REDIRECT $desc 失败: $output")
            }
        }
        return Pair(true, "")
    }

    /**
     * 清除透明代理规则（同时清理两种模式的残留规则）
     */
    private fun clearTransparentProxy() {
        val commands = listOf(
            // TPROXY 模式清理 (mangle 表)
            "iptables -t mangle -D OUTPUT -j $CHAIN_META 2>/dev/null",
            "iptables -t mangle -F $CHAIN_META 2>/dev/null",
            "iptables -t mangle -X $CHAIN_META 2>/dev/null",
            "ip rule del fwmark 1 table 100 2>/dev/null",
            "ip route del local 0.0.0.0/0 dev lo table 100 2>/dev/null",
            // REDIRECT 模式清理 (nat 表)
            "iptables -t nat -D OUTPUT -j $CHAIN_META 2>/dev/null",
            "iptables -t nat -F $CHAIN_META 2>/dev/null",
            "iptables -t nat -X $CHAIN_META 2>/dev/null",
        )
        RootChecker.execute(commands.joinToString(" ; "))
        currentProxyMode = "none"
    }

    /**
     * Apply lock background iptables rules.
     * 使用 CONNMARK 为连接打标记，配合 TPROXY 的 fwmark 实现后台锁定
     * @return Pair(success, errorMessage)
     */
    suspend fun applyLockBackground(): Pair<Boolean, String> {
        clearLockBackground()

        if (!ensureChain("mangle", CHAIN_LOCK_BG)) {
            return Pair(false, "锁定后台创建 chain 失败")
        }

        data class Step(val cmd: String, val desc: String)
        val steps = listOf(
            Step("iptables -t mangle -A $CHAIN_LOCK_BG -j CONNMARK --restore-mark", "恢复连接标记"),
            Step("iptables -t mangle -A $CHAIN_LOCK_BG -m mark ! --mark 0 -j RETURN", "跳过已标记连接"),
            Step("iptables -t mangle -A $CHAIN_LOCK_BG -j MARK --set-xmark 0x1/0x1", "设置 fwmark"),
            Step("iptables -t mangle -A $CHAIN_LOCK_BG -j CONNMARK --save-mark", "保存连接标记"),
            Step("iptables -t mangle -I OUTPUT -j $CHAIN_LOCK_BG", "应用 chain 到 OUTPUT"),
        )
        for ((cmd, desc) in steps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0) {
                clearLockBackground()
                return Pair(false, "锁定后台 $desc 失败: $output")
            }
        }
        return Pair(true, "")
    }

    /**
     * 清除锁定后台规则
     */
    private fun clearLockBackground() {
        val commands = listOf(
            "iptables -t mangle -D OUTPUT -j $CHAIN_LOCK_BG 2>/dev/null",
            "iptables -t mangle -F $CHAIN_LOCK_BG 2>/dev/null",
            "iptables -t mangle -X $CHAIN_LOCK_BG 2>/dev/null",
        )
        RootChecker.execute(commands.joinToString(" ; "))
    }

    /**
     * Apply DNS hijack iptables rules.
     * 将所有 DNS 查询（UDP/TCP 53 端口）重定向到 Clash 的 DNS 端口
     * @return Pair(success, errorMessage)
     */
    suspend fun applyDnsHijack(): Pair<Boolean, String> {
        clearDnsHijack()

        val proxyUid = getAppUid()

        if (!ensureChain("nat", CHAIN_DNS_HIJACK)) {
            return Pair(false, "DNS 劫持创建 chain 失败")
        }

        data class Step(val cmd: String, val desc: String)
        val steps = listOf(
            // 跳过代理进程自身的 DNS 查询（避免循环）
            Step("iptables -t nat -A $CHAIN_DNS_HIJACK -m owner --uid-owner $proxyUid -j RETURN", "跳过代理进程 DNS"),
            Step("iptables -t nat -A $CHAIN_DNS_HIJACK -p udp --dport 53 -j DNAT --to 127.0.0.1:$DNS_HIJACK_PORT", "劫持 UDP DNS"),
            Step("iptables -t nat -A $CHAIN_DNS_HIJACK -p tcp --dport 53 -j DNAT --to 127.0.0.1:$DNS_HIJACK_PORT", "劫持 TCP DNS"),
            Step("iptables -t nat -I OUTPUT -j $CHAIN_DNS_HIJACK", "应用 DNS 劫持 chain"),
        )

        for ((cmd, desc) in steps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0) {
                clearDnsHijack()
                return Pair(false, "$desc 失败: $output")
            }
        }
        return Pair(true, "")
    }

    /**
     * 清除 DNS 劫持规则
     */
    private fun clearDnsHijack() {
        val commands = listOf(
            "iptables -t nat -D OUTPUT -j $CHAIN_DNS_HIJACK 2>/dev/null",
            "iptables -t nat -F $CHAIN_DNS_HIJACK 2>/dev/null",
            "iptables -t nat -X $CHAIN_DNS_HIJACK 2>/dev/null",
        )
        RootChecker.execute(commands.joinToString(" ; "))
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
     * Clear all iptables rules applied by this helper.
     */
    suspend fun clearAllRules() {
        clearTransparentProxy()
        clearLockBackground()
        clearDnsHijack()
    }
}
