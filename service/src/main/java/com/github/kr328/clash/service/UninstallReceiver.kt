package com.github.kr328.clash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.log.Log

class UninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
            val packageName = intent.data?.schemeSpecificPart
            if (packageName == context.packageName) {
                Log.d("Package $packageName removed, cleaning up DNS hijack rules")
                cleanupDnsHijack()
            }
        }
    }

    private fun cleanupDnsHijack() {
        try {
            // 清理 IPv4 DNS 劫持规则
            for (table in listOf("nat", "mangle")) {
                execute("iptables -t $table -D PREROUTING -j CLASH_DNS_EXTERNAL 2>/dev/null")
                execute("iptables -t $table -D OUTPUT -j CLASH_DNS_LOCAL 2>/dev/null")
                execute("iptables -t $table -F CLASH_DNS_EXTERNAL 2>/dev/null")
                execute("iptables -t $table -X CLASH_DNS_EXTERNAL 2>/dev/null")
                execute("iptables -t $table -F CLASH_DNS_LOCAL 2>/dev/null")
                execute("iptables -t $table -X CLASH_DNS_LOCAL 2>/dev/null")

                // IPv6
                execute("ip6tables -t $table -D PREROUTING -j CLASH_DNS_EXTERNAL_V6 2>/dev/null")
                execute("ip6tables -t $table -D OUTPUT -j CLASH_DNS_LOCAL_V6 2>/dev/null")
                execute("ip6tables -t $table -F CLASH_DNS_EXTERNAL_V6 2>/dev/null")
                execute("ip6tables -t $table -X CLASH_DNS_EXTERNAL_V6 2>/dev/null")
                execute("ip6tables -t $table -F CLASH_DNS_LOCAL_V6 2>/dev/null")
                execute("ip6tables -t $table -X CLASH_DNS_LOCAL_V6 2>/dev/null")
            }
            Log.d("DNS hijack rules cleaned up successfully")
        } catch (e: Exception) {
            Log.e("Failed to cleanup DNS hijack rules", e)
        }
    }

    private fun execute(command: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        } catch (e: Exception) {
            // Ignore
        }
    }
}
