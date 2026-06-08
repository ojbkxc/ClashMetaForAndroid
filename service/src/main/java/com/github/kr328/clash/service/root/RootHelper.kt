package com.github.kr328.clash.service.root

import com.github.kr328.clash.common.RootChecker

object RootHelper {
    private const val TPROXY_PORT = 7892
    private const val DNS_HIJACK_PORT = 1053
    private const val PROXY_UID = 1000
    private const val DNS_UID = 1051

    /**
     * Apply transparent proxy iptables rules (TPROXY mode).
     * @return Pair(success, errorMessage)
     */
    suspend fun applyTransparentProxy(): Pair<Boolean, String> {
        val commands = listOf(
            // Create chain
            "iptables -t mangle -N CLASH_META 2>/dev/null",
            // Bypass local traffic
            "iptables -t mangle -I CLASH_META -m owner --uid-owner $PROXY_UID -j RETURN",
            "iptables -t mangle -I CLASH_META -m owner --uid-owner $DNS_UID -j RETURN",
            // Redirect TCP to TPROXY
            "iptables -t mangle -A CLASH_META -p tcp -j TPROXY --tproxy-mark 0x1/0x1 --on-port $TPROXY_PORT",
            "iptables -t mangle -A CLASH_META -p udp -j TPROXY --tproxy-mark 0x1/0x1 --on-port $TPROXY_PORT",
            // Apply chain to OUTPUT
            "iptables -t mangle -I OUTPUT -j CLASH_META",
            // Routing rules
            "ip rule add fwmark 1 table 100",
            "ip route add local 0.0.0.0/0 dev lo table 100",
        )
        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) Pair(true, "") else Pair(false, output)
    }

    /**
     * Apply lock background iptables rules.
     */
    suspend fun applyLockBackground() {
        val commands = listOf(
            "iptables -t mangle -N CLASH_LOCK_BG 2>/dev/null",
            "iptables -t mangle -F CLASH_LOCK_BG",
            "iptables -t mangle -A CLASH_LOCK_BG -j CONNMARK --restore-mark",
            "iptables -t mangle -A CLASH_LOCK_BG -m mark ! --mark 0 -j RETURN",
            "iptables -t mangle -A CLASH_LOCK_BG -j MARK --set-xmark 0x1/0x1",
            "iptables -t mangle -A CLASH_LOCK_BG -j CONNMARK --save-mark",
            "iptables -t mangle -I OUTPUT -j CLASH_LOCK_BG",
        )
        RootChecker.executeBatch(commands)
    }

    /**
     * Apply DNS hijack iptables rules.
     * @return Pair(success, errorMessage)
     */
    suspend fun applyDnsHijack(): Pair<Boolean, String> {
        val commands = listOf(
            "iptables -t nat -N CLASH_DNS_HIJACK 2>/dev/null",
            "iptables -t nat -F CLASH_DNS_HIJACK",
            "iptables -t nat -A CLASH_DNS_HIJACK -p udp --dport 53 -j DNAT --to 127.0.0.1:$DNS_HIJACK_PORT",
            "iptables -t nat -A CLASH_DNS_HIJACK -p tcp --dport 53 -j DNAT --to 127.0.0.1:$DNS_HIJACK_PORT",
            "iptables -t nat -I OUTPUT -j CLASH_DNS_HIJACK",
        )
        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) Pair(true, "") else Pair(false, output)
    }

    /**
     * Clear all iptables rules applied by this helper.
     */
    suspend fun clearAllRules() {
        val commands = listOf(
            // Remove chains from OUTPUT
            "iptables -t mangle -D OUTPUT -j CLASH_META 2>/dev/null",
            "iptables -t mangle -D OUTPUT -j CLASH_LOCK_BG 2>/dev/null",
            "iptables -t nat -D OUTPUT -j CLASH_DNS_HIJACK 2>/dev/null",
            // Flush and delete chains
            "iptables -t mangle -F CLASH_META 2>/dev/null",
            "iptables -t mangle -X CLASH_META 2>/dev/null",
            "iptables -t mangle -F CLASH_LOCK_BG 2>/dev/null",
            "iptables -t mangle -X CLASH_LOCK_BG 2>/dev/null",
            "iptables -t nat -F CLASH_DNS_HIJACK 2>/dev/null",
            "iptables -t nat -X CLASH_DNS_HIJACK 2>/dev/null",
            // Remove routing rules
            "ip rule del fwmark 1 table 100 2>/dev/null",
            "ip route del local 0.0.0.0/0 dev lo table 100 2>/dev/null",
        )
        RootChecker.executeBatch(commands)
    }
}
