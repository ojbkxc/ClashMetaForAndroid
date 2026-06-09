package com.github.kr328.clash.service.root

import com.github.kr328.clash.common.RootChecker

object RootHelper {
    private const val TAG = "RootHelper"
    private const val TPROXY_PORT = 7892
    private const val DNS_HIJACK_PORT = 1053
    private const val PROXY_UID = 1000
    private const val DNS_UID = 1051

    // Chain names
    private const val CHAIN_META = "CLASH_META"
    private const val CHAIN_LOCK_BG = "CLASH_LOCK_BG"
    private const val CHAIN_DNS_HIJACK = "CLASH_DNS_HIJACK"

    /**
     * 检查 root 权限是否可用
     * 参考 Shizuku 的权限检查机制
     */
    fun isRootAvailable(): Boolean {
        return RootChecker.isRooted() && RootChecker.requestRoot()
    }

    /**
     * 重新请求 root 权限（带重试）
     * 参考 Shizuku 的 startRoot() 重试机制
     */
    fun requestRootWithRetry(): Boolean {
        return RootChecker.requestRootWithRetry()
    }

    /**
     * Apply transparent proxy iptables rules (TPROXY mode).
     * 参考 Shizuku 的命令执行方式，增加错误处理
     * @return Pair(success, errorMessage)
     */
    suspend fun applyTransparentProxy(): Pair<Boolean, String> {
        // 先清理旧规则
        clearTransparentProxy()

        val commands = listOf(
            // Create chain
            "iptables -t mangle -N $CHAIN_META",
            // Bypass local traffic
            "iptables -t mangle -I $CHAIN_META -m owner --uid-owner $PROXY_UID -j RETURN",
            "iptables -t mangle -I $CHAIN_META -m owner --uid-owner $DNS_UID -j RETURN",
            // Redirect TCP to TPROXY
            "iptables -t mangle -A $CHAIN_META -p tcp -j TPROXY --tproxy-mark 0x1/0x1 --on-port $TPROXY_PORT",
            "iptables -t mangle -A $CHAIN_META -p udp -j TPROXY --tproxy-mark 0x1/0x1 --on-port $TPROXY_PORT",
            // Apply chain to OUTPUT
            "iptables -t mangle -I OUTPUT -j $CHAIN_META",
            // Routing rules
            "ip rule add fwmark 1 table 100",
            "ip route add local 0.0.0.0/0 dev lo table 100",
        )
        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) Pair(true, "") else Pair(false, output)
    }

    /**
     * 清除透明代理规则
     */
    private fun clearTransparentProxy() {
        val commands = listOf(
            "iptables -t mangle -D OUTPUT -j $CHAIN_META 2>/dev/null",
            "iptables -t mangle -F $CHAIN_META 2>/dev/null",
            "iptables -t mangle -X $CHAIN_META 2>/dev/null",
            "ip rule del fwmark 1 table 100 2>/dev/null",
            "ip route del local 0.0.0.0/0 dev lo table 100 2>/dev/null",
        )
        RootChecker.executeBatch(commands)
    }

    /**
     * Apply lock background iptables rules.
     */
    suspend fun applyLockBackground() {
        // 先清理旧规则
        clearLockBackground()

        val commands = listOf(
            "iptables -t mangle -N $CHAIN_LOCK_BG",
            "iptables -t mangle -A $CHAIN_LOCK_BG -j CONNMARK --restore-mark",
            "iptables -t mangle -A $CHAIN_LOCK_BG -m mark ! --mark 0 -j RETURN",
            "iptables -t mangle -A $CHAIN_LOCK_BG -j MARK --set-xmark 0x1/0x1",
            "iptables -t mangle -A $CHAIN_LOCK_BG -j CONNMARK --save-mark",
            "iptables -t mangle -I OUTPUT -j $CHAIN_LOCK_BG",
        )
        RootChecker.executeBatch(commands)
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
        RootChecker.executeBatch(commands)
    }

    /**
     * Apply DNS hijack iptables rules.
     * @return Pair(success, errorMessage)
     */
    suspend fun applyDnsHijack(): Pair<Boolean, String> {
        // 先清理旧规则
        clearDnsHijack()

        val commands = listOf(
            "iptables -t nat -N $CHAIN_DNS_HIJACK",
            "iptables -t nat -A $CHAIN_DNS_HIJACK -p udp --dport 53 -j DNAT --to 127.0.0.1:$DNS_HIJACK_PORT",
            "iptables -t nat -A $CHAIN_DNS_HIJACK -p tcp --dport 53 -j DNAT --to 127.0.0.1:$DNS_HIJACK_PORT",
            "iptables -t nat -I OUTPUT -j $CHAIN_DNS_HIJACK",
        )
        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) Pair(true, "") else Pair(false, output)
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
        RootChecker.executeBatch(commands)
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
