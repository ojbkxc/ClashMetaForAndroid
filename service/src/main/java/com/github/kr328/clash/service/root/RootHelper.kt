package com.github.kr328.clash.service.root

import android.util.Log
import com.github.kr328.clash.common.RootChecker

/**
 * Root 功能实现类
 * 所有 iptables 规则都在此类管理，确保规则的一致性和可清理性
 */
object RootHelper {
    private const val TAG = "RootHelper"

    // Clash 本地代理端口
    private const val CLASH_DNS_PORT = 1053
    private const val CLASH_SOCKS_PORT = 7891
    private const val CLASH_HTTP_PORT = 7890
    private const val CLASH_TPROXY_PORT = 7893

    // iptables 链名称（用于统一管理）
    private const val CHAIN_NAME = "CLASH_PROXY"

    /**
     * 透明代理：通过 iptables 重定向所有 TCP/UDP 流量到 Clash
     * 替代 VPN 模式，不会显示 VPN 图标
     */
    fun applyTransparentProxy(): Pair<Boolean, String> {
        val commands = listOf(
            // 创建自定义链
            "iptables -t nat -N $CHAIN_NAME 2>/dev/null || true",
            "iptables -t nat -F $CHAIN_NAME",

            // 跳过本地和 Clash 自身流量
            "iptables -t nat -A $CHAIN_NAME -d 127.0.0.0/8 -j RETURN",
            "iptables -t nat -A $CHAIN_NAME -d 10.0.0.0/8 -j RETURN",
            "iptables -t nat -A $CHAIN_NAME -d 172.16.0.0/12 -j RETURN",
            "iptables -t nat -A $CHAIN_NAME -d 192.168.0.0/16 -j RETURN",
            // 跳过 Clash 进程自身的流量（避免回环）
            "iptables -t nat -A $CHAIN_NAME -m owner --uid-owner $(pm list packages -U com.github.kr328.clash 2>/dev/null | head -1 | sed 's/.*uid://' || echo 10000) -j RETURN 2>/dev/null || true",

            // TCP 透明代理（重定向到 Clash HTTP 端口）
            "iptables -t nat -A $CHAIN_NAME -p tcp -j REDIRECT --to-ports $CLASH_HTTP_PORT",

            // 应用到 OUTPUT 链
            "iptables -t nat -C OUTPUT -j $CHAIN_NAME 2>/dev/null || iptables -t nat -A OUTPUT -j $CHAIN_NAME"
        )

        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) {
            Log.i(TAG, "Transparent proxy applied")
            Pair(true, "")
        } else {
            Log.w(TAG, "Transparent proxy failed: $output")
            Pair(false, output)
        }
    }

    /**
     * 锁定后台：防止系统杀掉 Clash 服务
     */
    fun applyLockBackground(): Pair<Boolean, String> {
        val commands = listOf(
            // 设置进程优先级为前台
            "echo -17 > /proc/\$(pidof com.github.kr328.clash)/oom_adj 2>/dev/null || true",
            "echo -1000 > /proc/\$(pidof com.github.kr328.clash)/oom_score_adj 2>/dev/null || true",

            // 禁用电池优化（通过 dumpsys）
            "dumpsys deviceidle whitelist +com.github.kr328.clash 2>/dev/null || true",

            // 设置为不可杀死
            "am set-inactive com.github.kr328.clash false 2>/dev/null || true"
        )

        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) {
            Log.i(TAG, "Lock background applied")
            Pair(true, "")
        } else {
            Log.w(TAG, "Lock background failed: $output")
            Pair(false, output)
        }
    }

    /**
     * Root DNS 劫持：通过 iptables 劫持所有 DNS 查询到 Clash
     */
    fun applyDnsHijack(): Pair<Boolean, String> {
        val commands = listOf(
            // 创建 DNS 劫持链
            "iptables -t nat -N CLASH_DNS 2>/dev/null || true",
            "iptables -t nat -F CLASH_DNS",

            // 跳过 Clash 自身的 DNS 请求
            "iptables -t nat -A CLASH_DNS -m owner --uid-owner \$(pm list packages -U com.github.kr328.clash 2>/dev/null | head -1 | sed 's/.*uid://' || echo 10000) -j RETURN 2>/dev/null || true",

            // 劫持所有 UDP 53 端口（DNS）到 Clash DNS 端口
            "iptables -t nat -A CLASH_DNS -p udp --dport 53 -j REDIRECT --to-ports $CLASH_DNS_PORT",

            // 劫持所有 TCP 53 端口（DNS over TCP）
            "iptables -t nat -A CLASH_DNS -p tcp --dport 53 -j REDIRECT --to-ports $CLASH_DNS_PORT",

            // 应用到 OUTPUT 链
            "iptables -t nat -C OUTPUT -j CLASH_DNS 2>/dev/null || iptables -t nat -A OUTPUT -j CLASH_DNS"
        )

        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) {
            Log.i(TAG, "DNS hijack applied")
            Pair(true, "")
        } else {
            Log.w(TAG, "DNS hijack failed: $output")
            Pair(false, output)
        }
    }

    /**
     * 清除所有 Clash 相关的 iptables 规则
     * 在关闭 Root 功能或卸载时调用
     */
    fun clearAllRules(): Pair<Boolean, String> {
        val commands = listOf(
            // 清除透明代理规则
            "iptables -t nat -D OUTPUT -j $CHAIN_NAME 2>/dev/null || true",
            "iptables -t nat -F $CHAIN_NAME 2>/dev/null || true",
            "iptables -t nat -X $CHAIN_NAME 2>/dev/null || true",

            // 清除 DNS 劫持规则
            "iptables -t nat -D OUTPUT -j CLASH_DNS 2>/dev/null || true",
            "iptables -t nat -F CLASH_DNS 2>/dev/null || true",
            "iptables -t nat -X CLASH_DNS 2>/dev/null || true"
        )

        val (code, output) = RootChecker.executeBatch(commands)
        return if (code == 0) {
            Log.i(TAG, "All root rules cleared")
            Pair(true, "")
        } else {
            Log.w(TAG, "Clear rules failed: $output")
            Pair(false, output)
        }
    }
}
