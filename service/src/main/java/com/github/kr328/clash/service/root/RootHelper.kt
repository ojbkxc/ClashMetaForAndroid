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
    private const val CHAIN_DIVERT = "CLASH_DIVERT"

    // 路由标记和表ID（参考 Surfing）
    private const val MARK_ID = "0x1/0x1"
    private const val TABLE_ID = "100"
    private const val TABLE_PREF = "100"

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
        RootChecker.execute("iptables -t $table -D PREROUTING -j $chain 2>/dev/null")
        RootChecker.execute("iptables -t $table -F $chain 2>/dev/null")
        RootChecker.execute("iptables -t $table -X $chain 2>/dev/null")
        // 创建新 chain
        val (code, _) = RootChecker.execute("iptables -t $table -N $chain")
        return code == 0
    }

    /**
     * 检测内核是否支持 TPROXY
     */
    private fun isTProxySupported(): Boolean {
        // 先清理可能残留的测试 chain
        RootChecker.execute("iptables -t mangle -F CLASH_TPROXY_TEST 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X CLASH_TPROXY_TEST 2>/dev/null")
        // 创建测试 chain
        val (c1, _) = RootChecker.execute("iptables -t mangle -N CLASH_TPROXY_TEST")
        if (c1 != 0) return false
        // 尝试添加 TPROXY 规则（同时测试 TCP 和 UDP）
        val (c2, _) = RootChecker.execute("iptables -t mangle -A CLASH_TPROXY_TEST -p tcp -j TPROXY --tproxy-mark 0x1/0x1 --on-port 1")
        val (c3, _) = RootChecker.execute("iptables -t mangle -A CLASH_TPROXY_TEST -p udp -j TPROXY --tproxy-mark 0x1/0x1 --on-port 1")
        // 清理测试 chain
        RootChecker.execute("iptables -t mangle -F CLASH_TPROXY_TEST 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X CLASH_TPROXY_TEST 2>/dev/null")
        return c2 == 0 && c3 == 0
    }

    /**
     * 优化内核参数，提升 UDP 性能（参考 Surfing）
     */
    private fun optimizeKernel(): Boolean {
        // 尝试设置 UDP conntrack 超时
        val commands = listOf(
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout=30 2>/dev/null",
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout_stream=15 2>/dev/null",
            // 直接写入 proc 文件（某些设备 sysctl 被禁用）
            "echo 30 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout 2>/dev/null",
            "echo 15 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout_stream 2>/dev/null"
        )
        for (cmd in commands) {
            RootChecker.execute(cmd)
        }
        return true
    }

    /**
     * Apply transparent proxy iptables rules.
     * 优先使用 TPROXY 模式（支持 TCP+UDP），不支持时回退到 REDIRECT 模式（仅 TCP）
     * 参考 Surfing 的 iptables 方案
     * @return Pair(success, errorMessage)
     */
    suspend fun applyTransparentProxy(): Pair<Boolean, String> {
        clearTransparentProxy()

        val proxyUid = getAppUid()
        if (proxyUid < 0) {
            return Pair(false, "无法获取应用 UID，请确认应用已正确安装")
        }

        // 优化内核参数
        optimizeKernel()

        // 检测 TPROXY 支持（现在会测试 UDP）
        if (isTProxySupported()) {
            val result = applyTProxy(proxyUid)
            if (result.first) {
                currentProxyMode = "tproxy"
                return Pair(true, "TPROXY 模式（支持 TCP+UDP）")
            }
            clearTransparentProxy()
        }

        // 回退到 REDIRECT 模式（仅 TCP，兼容所有设备）
        val result = applyRedirect(proxyUid)
        if (result.first) {
            currentProxyMode = "redirect"
            return Pair(true, "REDIRECT 模式（仅支持 TCP）")
        }
        return result
    }

    /**
     * TPROXY 模式：支持 TCP + UDP，需要内核支持
     * 参考 Surfing 的完整实现方案
     */
    private fun applyTProxy(proxyUid: Int): Pair<Boolean, String> {
        // 1. 设置路由规则（参考 Surfing）
        val routeSteps = listOf(
            // 删除旧的路由规则（如果存在）
            Pair("ip rule del fwmark 1 table $TABLE_ID 2>/dev/null", "清理旧路由规则"),
            Pair("ip route del local 0.0.0.0/0 dev lo table $TABLE_ID 2>/dev/null", "清理旧路由表"),
            // 添加新的路由规则
            Pair("ip rule add fwmark 1 table $TABLE_ID pref $TABLE_PREF", "添加路由规则"),
            Pair("ip route add local 0.0.0.0/0 dev lo table $TABLE_ID", "添加本地路由表"),
        )
        for ((cmd, desc) in routeSteps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0 && desc.contains("添加")) {
                return Pair(false, "TPROXY $desc 失败: $output")
            }
        }

        // 2. 创建 mangle 表的 CHAIN_META chain
        if (!ensureChain("mangle", CHAIN_META)) {
            return Pair(false, "TPROXY 创建 mangle chain 失败")
        }

        // 3. 添加 socket 透明连接处理（关键！参考 Surfing）
        // 透明 socket 连接直接打标记，避免重复处理
        RootChecker.execute("iptables -t mangle -A $CHAIN_META -p tcp -m socket -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_META -p udp -m socket -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_META -m socket -j RETURN")

        // 4. 跳过局域网流量（参考 Surfing 的 intranet 方案）
        val bypassCommands = listOf(
            "iptables -t mangle -A $CHAIN_META -d 127.0.0.0/8 -j RETURN",
            "iptables -t mangle -A $CHAIN_META -d 10.0.0.0/8 -j RETURN",
            "iptables -t mangle -A $CHAIN_META -d 172.16.0.0/12 -j RETURN",
            "iptables -t mangle -A $CHAIN_META -d 192.168.0.0/16 -j RETURN",
            "iptables -t mangle -A $CHAIN_META -d 224.0.0.0/4 -j RETURN",
            "iptables -t mangle -A $CHAIN_META -d 240.0.0.0/4 -j RETURN",
        )
        for (cmd in bypassCommands) {
            RootChecker.execute(cmd)
        }

        // 5. CONNMARK 恢复标记（参考 Surfing：在规则链开始时恢复连接标记）
        RootChecker.execute("iptables -t mangle -I $CHAIN_META 1 -j CONNMARK --restore-mark")
        RootChecker.execute("iptables -t mangle -I $CHAIN_META 2 -m mark --mark 0 -j RETURN")

        // 6. 跳过代理进程自身流量
        RootChecker.execute("iptables -t mangle -A $CHAIN_META -m owner --uid-owner $proxyUid -j RETURN")

        // 7. CONNMARK 保存标记（在规则链末尾保存连接标记）
        RootChecker.execute("iptables -t mangle -A $CHAIN_META -j CONNMARK --save-mark")

        // 8. 添加到 PREROUTING 链（处理来自其他应用的数据包）
        RootChecker.execute("iptables -t mangle -I PREROUTING -j $CHAIN_META")

        // 9. 添加 TPROXY 规则（同时支持 TCP 和 UDP）
        val tproxySteps = listOf(
            Pair("iptables -t mangle -A $CHAIN_META -p tcp -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT", "设置 TCP TPROXY"),
            Pair("iptables -t mangle -A $CHAIN_META -p udp -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT", "设置 UDP TPROXY"),
        )
        for ((cmd, desc) in tproxySteps) {
            val (code, output) = RootChecker.execute(cmd)
            if (code != 0) return Pair(false, "TPROXY $desc 失败: $output")
        }

        // 10. 创建 OUTPUT 链处理本地发出的流量
        if (!ensureChain("mangle", "${CHAIN_META}_LOCAL")) {
            return Pair(false, "TPROXY 创建 OUTPUT chain 失败")
        }

        val outputChain = "${CHAIN_META}_LOCAL"
        val outputSteps = listOf(
            // CONNMARK 恢复
            Pair("iptables -t mangle -A $outputChain -j CONNMARK --restore-mark", "OUTPUT恢复标记"),
            Pair("iptables -t mangle -A $outputChain -m mark --mark 0 -j RETURN", "OUTPUT跳过已标记"),
            // 跳过代理进程
            Pair("iptables -t mangle -A $outputChain -m owner --uid-owner $proxyUid -j RETURN", "OUTPUT跳过代理进程"),
            // 跳过局域网
            Pair("iptables -t mangle -A $outputChain -d 127.0.0.0/8 -j RETURN", "OUTPUT跳过回环"),
            Pair("iptables -t mangle -A $outputChain -d 10.0.0.0/8 -j RETURN", "OUTPUT跳过10.x"),
            Pair("iptables -t mangle -A $outputChain -d 172.16.0.0/12 -j RETURN", "OUTPUT跳过172.16.x"),
            Pair("iptables -t mangle -A $outputChain -d 192.168.0.0/16 -j RETURN", "OUTPUT跳过192.168.x"),
            Pair("iptables -t mangle -A $outputChain -d 224.0.0.0/4 -j RETURN", "OUTPUT跳过组播"),
            Pair("iptables -t mangle -A $outputChain -d 240.0.0.0/4 -j RETURN", "OUTPUT跳过保留"),
            // 设置标记
            Pair("iptables -t mangle -A $outputChain -p tcp -j MARK --set-xmark $MARK_ID", "OUTPUT标记TCP"),
            Pair("iptables -t mangle -A $outputChain -p udp -j MARK --set-xmark $MARK_ID", "OUTPUT标记UDP"),
            // CONNMARK 保存
            Pair("iptables -t mangle -A $outputChain -j CONNMARK --save-mark", "OUTPUT保存标记"),
        )
        for ((cmd, desc) in outputSteps) {
            RootChecker.execute(cmd)
        }

        // 添加到 OUTPUT 链
        RootChecker.execute("iptables -t mangle -I OUTPUT -j $outputChain")

        // 11. 创建 DIVERT 链加速已建立连接（参考 Surfing）
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
     * REDIRECT 模式：仅支持 TCP，兼容所有 Android 设备
     */
    private fun applyRedirect(proxyUid: Int): Pair<Boolean, String> {
        if (!ensureChain("nat", CHAIN_META)) {
            return Pair(false, "REDIRECT 创建 chain 失败")
        }

        data class Step(val cmd: String, val desc: String)
        val steps = listOf(
            // 跳过代理进程流量
            Step("iptables -t nat -A $CHAIN_META -m owner --uid-owner $proxyUid -j RETURN", "跳过代理进程流量"),
            // 跳过局域网流量
            Step("iptables -t nat -A $CHAIN_META -d 127.0.0.0/8 -j RETURN", "跳过本地回环"),
            Step("iptables -t nat -A $CHAIN_META -d 10.0.0.0/8 -j RETURN", "跳过局域网 10.x"),
            Step("iptables -t nat -A $CHAIN_META -d 172.16.0.0/12 -j RETURN", "跳过局域网 172.16.x"),
            Step("iptables -t nat -A $CHAIN_META -d 192.168.0.0/16 -j RETURN", "跳过局域网 192.168.x"),
            // TCP 重定向
            Step("iptables -t nat -A $CHAIN_META -p tcp -j REDIRECT --to-ports $TPROXY_PORT", "设置 TCP REDIRECT"),
            // 应用到 OUTPUT 链
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
        val outputChain = "${CHAIN_META}_LOCAL"
        val commands = listOf(
            // 清理路由规则
            "ip rule del fwmark 1 table $TABLE_ID 2>/dev/null",
            "ip route del local 0.0.0.0/0 dev lo table $TABLE_ID 2>/dev/null",
            // 清理 DIVERT 链
            "iptables -t mangle -D PREROUTING -p tcp -m socket -j $CHAIN_DIVERT 2>/dev/null",
            "iptables -t mangle -F $CHAIN_DIVERT 2>/dev/null",
            "iptables -t mangle -X $CHAIN_DIVERT 2>/dev/null",
            // 清理 OUTPUT chain
            "iptables -t mangle -D OUTPUT -j $outputChain 2>/dev/null",
            "iptables -t mangle -F $outputChain 2>/dev/null",
            "iptables -t mangle -X $outputChain 2>/dev/null",
            // 清理 mangle 表
            "iptables -t mangle -D PREROUTING -j $CHAIN_META 2>/dev/null",
            "iptables -t mangle -D OUTPUT -j $CHAIN_META 2>/dev/null",
            "iptables -t mangle -F $CHAIN_META 2>/dev/null",
            "iptables -t mangle -X $CHAIN_META 2>/dev/null",
            // 清理 nat 表
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
            Step("iptables -t mangle -A $CHAIN_LOCK_BG -j MARK --set-xmark $MARK_ID", "设置 fwmark"),
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
     * 使用 REDIRECT 模式（参考 Surfing 的 DNS 劫持方案）
     * @return Pair(success, errorMessage)
     */
    suspend fun applyDnsHijack(): Pair<Boolean, String> {
        clearDnsHijack()

        val proxyUid = getAppUid()

        // 1. 创建 nat 表的 DNS 劫持 chain
        if (!ensureChain("nat", CHAIN_DNS_HIJACK)) {
            return Pair(false, "DNS 劫持创建 chain 失败")
        }

        // 2. 跳过代理进程自身的 DNS 查询（避免循环）
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_HIJACK -m owner --uid-owner $proxyUid -j RETURN")

        // 3. DNS 重定向规则（使用 REDIRECT，参考 Surfing）
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_HIJACK -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_HIJACK -p tcp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")

        // 4. 添加到 OUTPUT 链
        RootChecker.execute("iptables -t nat -I OUTPUT -j $CHAIN_DNS_HIJACK")

        // 5. 添加到 PREROUTING 链（处理来自其他应用的数据包）
        RootChecker.execute("iptables -t nat -I PREROUTING -j $CHAIN_DNS_HIJACK")

        return Pair(true, "")
    }

    /**
     * 清除 DNS 劫持规则
     */
    private fun clearDnsHijack() {
        val commands = listOf(
            "iptables -t nat -D OUTPUT -j $CHAIN_DNS_HIJACK 2>/dev/null",
            "iptables -t nat -D PREROUTING -j $CHAIN_DNS_HIJACK 2>/dev/null",
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
