package com.github.kr328.clash.service.root

import com.github.kr328.clash.common.RootChecker
import kotlinx.coroutines.delay

object RootHelper {
    private const val TPROXY_PORT = 7892
    private const val DNS_HIJACK_PORT = 1053
    private const val PACKAGE_NAME = "com.github.kr328.clash"

    // Chain names - unified naming style (refer to Surfing)
    private const val CHAIN_EXTERNAL = "CLASH_EXTERNAL"
    private const val CHAIN_LOCAL = "CLASH_LOCAL"
    private const val CHAIN_LOCK_BG = "CLASH_LOCK_BG"
    private const val CHAIN_DNS_EXTERNAL = "CLASH_DNS_EXTERNAL"
    private const val CHAIN_DNS_LOCAL = "CLASH_DNS_LOCAL"
    private const val CHAIN_DIVERT = "CLASH_DIVERT"
    private const val CHAIN_LOCAL_IP = "CLASH_LOCAL_IP"
    private const val CHAIN_LOCAL_IP_V6 = "CLASH_LOCAL_IP_V6"
    
    // IPv6 chain names
    private const val CHAIN_EXTERNAL_V6 = "CLASH_EXTERNAL_V6"
    private const val CHAIN_LOCAL_V6 = "CLASH_LOCAL_V6"
    private const val CHAIN_DNS_EXTERNAL_V6 = "CLASH_DNS_EXTERNAL_V6"
    private const val CHAIN_DNS_LOCAL_V6 = "CLASH_DNS_LOCAL_V6"
    private const val CHAIN_DIVERT_V6 = "CLASH_DIVERT_V6"

    // Routing mark and table ID (reference Surfing)
    private const val MARK_ID = "0x1/0x1"
    private const val MARK_VALUE = "0x1"
    private const val TABLE_ID = "100"
    private const val TABLE_PREF = "100"

    // DNS hijack mode: true = use nat table REDIRECT, false = use mangle table TPROXY
    @Volatile
    private var useNatTableForDns = true

    // Cached dynamically obtained UID
    @Volatile
    private var cachedProxyUid: Int = -1
    
    // Retry configuration
    private const val MAX_RETRY_COUNT = 2
    private const val RETRY_DELAY_MS = 1000L

    /**
     * Initialize app UID (called by Activity, passing applicationInfo.uid)
     */
    fun initAppUid(uid: Int) {
        cachedProxyUid = uid
    }

    /**
     * Check if root permission is available
     */
    fun isRootAvailable(): Boolean {
        return RootChecker.isRooted() && RootChecker.requestRoot()
    }

    /**
     * Request root permission again (with retry)
     */
    fun requestRootWithRetry(): Boolean {
        return RootChecker.requestRootWithRetry()
    }

    /**
     * Set DNS hijack mode
     * @param useNatTable true=use nat table REDIRECT, false=use mangle table TPROXY
     */
    fun setDnsHijackMode(useNatTable: Boolean) {
        useNatTableForDns = useNatTable
    }

    /**
     * Get app UID
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
     * Get local IPv4 address list
     */
    private fun getLocalIpv4Addresses(): List<String> {
        val (code, output) = RootChecker.execute("ip -4 a | grep inet | awk '{print \$2}' | grep -vE '^127.0.0.1'")
        if (code != 0) return emptyList()
        return output.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("::") }
    }

    /**
     * Get local IPv6 address list
     */
    private fun getLocalIpv6Addresses(): List<String> {
        val (code, output) = RootChecker.execute("ip -6 a | grep inet6 | awk '{print \$2}' | grep -vE '^fe80|^::1|^::/'")
        if (code != 0) return emptyList()
        return output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Get hotspot network interface list (using wildcard matching, refer to Surfing)
     */
    private fun getHotspotInterfaces(): List<String> {
        val hotspotIfaces = mutableSetOf<String>()

        val (code, output) = RootChecker.execute("ip link show | cut -d: -f2 | tr -d ' '")
        if (code != 0 || output.isEmpty()) return emptyList()

        val allInterfaces = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val wildcardPatterns = listOf("wlan", "ap", "rndis", "ncm", "eth", "p2p")

        for (iface in allInterfaces) {
            for (pattern in wildcardPatterns) {
                if (iface.startsWith(pattern) && iface.length > pattern.length) {
                    val suffix = iface.substring(pattern.length)
                    if (suffix.matches(Regex("^[0-9].*") ) || suffix.matches(Regex("^[-_].*"))) {
                        hotspotIfaces.add(iface)
                        break
                    }
                }
            }
        }

        return hotspotIfaces.toList()
    }

    /**
     * Get hotspot interface IP subnets
     */
    private fun getHotspotSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        val hotspotIfaces = getHotspotInterfaces()

        for (iface in hotspotIfaces) {
            val (code, output) = RootChecker.execute("ip -4 addr show $iface | grep inet | awk '{print \$2}'")
            if (code == 0 && output.isNotEmpty()) {
                output.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { ip ->
                    if (!ip.contains(":")) {
                        subnets.add(ip)
                    }
                }
            }
        }

        return subnets
    }

    /**
     * Check if a subnet is contained within a parent subnet
     */
    private fun isSubnetContained(subnet: String, parentSubnet: String): Boolean {
        try {
            val (subnetIp, subnetMask) = subnet.split("/")
            val (parentIp, parentMask) = parentSubnet.split("/")

            val subnetPrefix = subnetMask.toIntOrNull() ?: return false
            val parentPrefix = parentMask.toIntOrNull() ?: return false

            if (parentPrefix >= subnetPrefix) {
                return false
            }

            val subnetIpInt = ipToInt(subnetIp)
            val parentIpInt = ipToInt(parentIp)

            val subnetMaskInt = if (subnetPrefix == 0) 0 else (0xFFFFFFFF shl (32 - subnetPrefix))
            val parentMaskInt = if (parentPrefix == 0) 0 else (0xFFFFFFFF shl (32 - parentPrefix))

            return (subnetIpInt and parentMaskInt) == (parentIpInt and parentMaskInt)
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Convert IPv4 address to integer
     */
    private fun ipToInt(ip: String): Long {
        val parts = ip.split(".")
        if (parts.size != 4) return 0
        return (parts[0].toLongOrNull() ?: 0) * 256 * 256 * 256 +
               (parts[1].toLongOrNull() ?: 0) * 256 * 256 +
               (parts[2].toLongOrNull() ?: 0) * 256 +
               (parts[3].toLongOrNull() ?: 0)
    }

    /**
     * Create iptables chain, clean first if exists
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
     * Create ip6tables chain, clean first if exists
     */
    private fun ensureChainV6(table: String, chain: String): Boolean {
        RootChecker.execute("ip6tables -t $table -D OUTPUT -j $chain 2>/dev/null")
        RootChecker.execute("ip6tables -t $table -D PREROUTING -j $chain 2>/dev/null")
        RootChecker.execute("ip6tables -t $table -F $chain 2>/dev/null")
        RootChecker.execute("ip6tables -t $table -X $chain 2>/dev/null")
        val (code, _) = RootChecker.execute("ip6tables -t $table -N $chain")
        return code == 0
    }

    /**
     * Check if kernel supports TPROXY
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
     * Check if kernel supports IPv6 TPROXY
     */
    private fun isTProxyV6Supported(): Boolean {
        if (!RootChecker.isIp6tablesAvailable()) return false
        
        RootChecker.execute("ip6tables -t mangle -F CLASH_TPROXY_TEST_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -X CLASH_TPROXY_TEST_V6 2>/dev/null")
        val (c1, _) = RootChecker.execute("ip6tables -t mangle -N CLASH_TPROXY_TEST_V6")
        if (c1 != 0) return false
        val (c2, _) = RootChecker.execute("ip6tables -t mangle -A CLASH_TPROXY_TEST_V6 -p tcp -j TPROXY --tproxy-mark 0x1/0x1 --on-port 1")
        val (c3, _) = RootChecker.execute("ip6tables -t mangle -A CLASH_TPROXY_TEST_V6 -p udp -j TPROXY --tproxy-mark 0x1/0x1 --on-port 1")
        RootChecker.execute("ip6tables -t mangle -F CLASH_TPROXY_TEST_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -X CLASH_TPROXY_TEST_V6 2>/dev/null")
        return c2 == 0 && c3 == 0
    }

    /**
     * Optimize kernel parameters (refer to Surfing)
     * Includes comprehensive UDP buffer and performance optimizations
     */
    private fun optimizeKernel(): Boolean {
        val commands = listOf(
            // === UDP Performance Optimizations ===
            // UDP conntrack timeout optimization
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout=30 2>/dev/null",
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout_stream=15 2>/dev/null",
            "echo 30 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout 2>/dev/null",
            "echo 15 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout_stream 2>/dev/null",
            
            // UDP buffer optimization - comprehensive settings
            "sysctl -w net.core.optmem_max=4194304 2>/dev/null",
            "sysctl -w net.ipv4.udp_mem=\"65536 131072 262144\" 2>/dev/null",
            "sysctl -w net.ipv4.udp_rmem_min=8192 2>/dev/null",
            "sysctl -w net.ipv4.udp_wmem_min=8192 2>/dev/null",
            // UDP buffer max limits (increased for better throughput)
            "sysctl -w net.ipv4.udp_rmem_max=4194304 2>/dev/null",
            "sysctl -w net.ipv4.udp_wmem_max=4194304 2>/dev/null",
            
            // IPv6 UDP buffer optimization
            "sysctl -w net.ipv6.udp_mem=\"65536 131072 262144\" 2>/dev/null",
            "sysctl -w net.ipv6.udp_rmem_min=8192 2>/dev/null",
            "sysctl -w net.ipv6.udp_wmem_min=8192 2>/dev/null",
            // IPv6 UDP buffer max limits
            "sysctl -w net.ipv6.udp_rmem_max=4194304 2>/dev/null",
            "sysctl -w net.ipv6.udp_wmem_max=4194304 2>/dev/null",
            
            // IP fragmentation optimization (for large UDP packets)
            "sysctl -w net.ipv4.ipfrag_high_thresh=4194304 2>/dev/null",
            "sysctl -w net.ipv4.ipfrag_low_thresh=2097152 2>/dev/null",
            
            // === Network Queue Optimizations ===
            // Increase network device backlog (prevents packet drops under high load)
            "sysctl -w net.core.netdev_max_backlog=4096 2>/dev/null",
            // Increase socket listen backlog
            "sysctl -w net.core.somaxconn=4096 2>/dev/null",
            // Increase TCP SYN backlog
            "sysctl -w net.ipv4.tcp_max_syn_backlog=2048 2>/dev/null",
            
            // === Connection Tracking Optimizations ===
            // Increase conntrack max connections
            "sysctl -w net.netfilter.nf_conntrack_max=200000 2>/dev/null",
            "echo 200000 > /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null",
            // Increase conntrack hash table size (reduces collisions)
            "sysctl -w net.netfilter.nf_conntrack_buckets=65536 2>/dev/null",
            // TCP conntrack optimization
            "sysctl -w net.netfilter.nf_conntrack_tcp_timeout_established=3600 2>/dev/null",
            
            // === TCP Optimizations ===
            "sysctl -w net.ipv4.tcp_tw_reuse=1 2>/dev/null",
            // IP forward
            "sysctl -w net.ipv4.ip_forward=1 2>/dev/null",
            // TCP buffer optimization
            "sysctl -w net.ipv4.tcp_wmem=\"4096 16384 4194304\" 2>/dev/null",
            "sysctl -w net.ipv4.tcp_rmem=\"4096 87380 4194304\" 2>/dev/null",
            "sysctl -w net.core.rmem_max=4194304 2>/dev/null",
            "sysctl -w net.core.wmem_max=4194304 2>/dev/null",
            "sysctl -w net.core.rmem_default=262144 2>/dev/null",
            "sysctl -w net.core.wmem_default=262144 2>/dev/null",
            
            // === IPv6 Settings ===
            "sysctl -w net.ipv6.conf.all.forwarding=1 2>/dev/null",
            "sysctl -w net.ipv6.conf.default.forwarding=1 2>/dev/null",
            "sysctl -w net.ipv6.conf.all.accept_ra=2 2>/dev/null",
            
            // === Advanced Optimizations ===
            // TCP congestion control (try to use bbr if available)
            "sysctl -w net.ipv4.tcp_congestion_control=bbr 2>/dev/null",
            // Disable TCP timestamps (may help with some networks)
            "sysctl -w net.ipv4.tcp_timestamps=0 2>/dev/null",
            // Reduce SYN retry attempts for faster failure recovery
            "sysctl -w net.ipv4.tcp_synack_retries=2 2>/dev/null",
            
            // === Hysteria2/QUIC Specific Optimizations ===
            // Larger UDP buffer for QUIC-based protocols
            "sysctl -w net.ipv4.udp_mem=\"262144 524288 1048576\" 2>/dev/null",
            "sysctl -w net.ipv4.udp_rmem_min=16384 2>/dev/null",
            "sysctl -w net.ipv4.udp_wmem_min=16384 2>/dev/null",
            "sysctl -w net.ipv6.udp_rmem_min=16384 2>/dev/null",
            "sysctl -w net.ipv6.udp_wmem_min=16384 2>/dev/null",
            // Increase max UDP payload size (supports large QUIC packets)
            "sysctl -w net.core.max_udp_payload=65535 2>/dev/null",
            // Optimized QUIC connection tracking timeout
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout=10 2>/dev/null",
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout_stream=60 2>/dev/null"
        )
        for (cmd in commands) {
            RootChecker.execute(cmd)
        }
        return true
    }

    /**
     * Apply transparent proxy rules with retry mechanism
     */
    suspend fun applyTransparentProxy(): Pair<Boolean, String> {
        // Try with retry
        var lastError = ""
        for (retry in 0..MAX_RETRY_COUNT) {
            clearTransparentProxy()

            val proxyUid = getAppUid()
            if (proxyUid < 0) {
                lastError = "Cannot get app UID"
                continue
            }

            optimizeKernel()

            var success = false
            var mode = ""

            if (isTProxySupported()) {
                val result = applyTProxy(proxyUid)
                if (result.first) {
                    success = true
                    mode = "TPROXY mode (TCP+UDP supported)"
                } else {
                    lastError = "TPROXY failed: ${result.second}"
                    clearTransparentProxy()
                }
            }

            if (!success) {
                val result = applyRedirect(proxyUid)
                if (result.first) {
                    success = true
                    mode = "REDIRECT mode (TCP only)"
                } else {
                    lastError = "REDIRECT failed: ${result.second}"
                }
            }

            if (success) {
                // Apply IPv6 rules if supported
                if (isTProxyV6Supported()) {
                    applyTProxyV6(proxyUid)
                }
                return Pair(true, mode)
            }

            // Wait before retry using coroutine delay (non-blocking)
            if (retry < MAX_RETRY_COUNT) {
                delay(RETRY_DELAY_MS)
            }
        }

        return Pair(false, lastError)
    }

    /**
     * TPROXY mode - reference Surfing implementation
     */
    private fun applyTProxy(proxyUid: Int): Pair<Boolean, String> {
        val proxyUidStr = proxyUid.toString()

        // Setup routing rules (IPv4)
        RootChecker.execute("ip rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip route del local default dev lo table $TABLE_ID 2>/dev/null")
        var (code, output) = RootChecker.execute("ip rule add fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF")
        if (code != 0) return Pair(false, "Setup routing rules failed: $output")
        val (code2, output2) = RootChecker.execute("ip route add local default dev lo table $TABLE_ID")
        if (code2 != 0) return Pair(false, "Setup local routing table failed: $output2")

        // Create CLASH_EXTERNAL chain (PREROUTING)
        if (!ensureChain("mangle", CHAIN_EXTERNAL)) {
            return Pair(false, "Create CLASH_EXTERNAL chain failed")
        }

        // Socket transparent connection handling
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p tcp -m socket --transparent -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p udp -m socket --transparent -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -m socket -j RETURN")

        // DNS skip rules
        if (useNatTableForDns) {
            RootChecker.execute("iptables -t mangle -I $CHAIN_EXTERNAL 1 -p udp --dport 53 -j RETURN")
            RootChecker.execute("iptables -t mangle -I $CHAIN_EXTERNAL 2 -p tcp --dport 53 -j RETURN")
        }

        // Get hotspot subnets
        val hotspotSubnets = getHotspotSubnets()

        // Create LOCAL_IP chain
        if (!ensureChain("mangle", CHAIN_LOCAL_IP)) {
            return Pair(false, "Create LOCAL_IP chain failed")
        }
        val localIpv4List = getLocalIpv4Addresses()
        for (ip in localIpv4List) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL_IP -d $ip -j RETURN")
        }

        // Bypass LAN traffic (exclude hotspot subnets)
        val bypassSubnets = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.0.0.0/8", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16",
            "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32"
        )
        for (subnet in bypassSubnets) {
            var shouldSkip = false
            for (hotspotSubnet in hotspotSubnets) {
                if (isSubnetContained(hotspotSubnet, subnet)) {
                    shouldSkip = true
                    break
                }
            }
            if (shouldSkip) {
                continue
            }
            RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -d $subnet -j RETURN")
        }

        // Jump to LOCAL_IP chain
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -j $CHAIN_LOCAL_IP")

        // TPROXY rules on lo interface
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p tcp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p udp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")

        // Hotspot interface TPROXY rules
        val hotspotIfaces = getHotspotInterfaces()
        for (iface in hotspotIfaces) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p tcp -i $iface -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
            RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p udp -i $iface -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
        }

        // Add to PREROUTING chain
        RootChecker.execute("iptables -t mangle -I PREROUTING -j $CHAIN_EXTERNAL")

        // Create CLASH_LOCAL chain (OUTPUT)
        if (!ensureChain("mangle", CHAIN_LOCAL)) {
            return Pair(false, "Create CLASH_LOCAL chain failed")
        }

        // Position 1: Skip proxy process itself
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 1 -m owner --uid-owner $proxyUidStr -j RETURN")

        // Position 2: CONNMARK restore mark
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 2 -j CONNMARK --restore-mark")

        // Position 3: Skip already marked connections
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 3 -m mark --mark $MARK_ID -j ACCEPT")

        // Position 4-5: DNS skip rules
        if (useNatTableForDns) {
            RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 4 -p udp --dport 53 -j RETURN")
            RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 5 -p tcp --dport 53 -j RETURN")
        }

        // Skip LAN traffic
        for (subnet in bypassSubnets) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -d $subnet -j RETURN")
        }

        // Handle local IP addresses
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -j $CHAIN_LOCAL_IP")

        // Set mark (TCP and UDP)
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -p tcp -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -p udp -j MARK --set-xmark $MARK_ID")

        // End: CONNMARK save mark
        RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL -j CONNMARK --save-mark")

        // Add to OUTPUT chain
        RootChecker.execute("iptables -t mangle -I OUTPUT -j $CHAIN_LOCAL")

        // DNS hijack
        if (useNatTableForDns) {
            if (!applyDnsHijackNatMode(proxyUidStr)) {
                return Pair(false, "DNS hijack failed")
            }
        } else {
            if (!applyDnsHijackMangleMode(proxyUidStr, TPROXY_PORT)) {
                return Pair(false, "DNS hijack failed")
            }
        }

        // Create DIVERT chain - handle transparent socket connections
        if (!ensureChain("mangle", CHAIN_DIVERT)) {
            // DIVERT is not essential, continue
        } else {
            RootChecker.execute("iptables -t mangle -A $CHAIN_DIVERT -j MARK --set-xmark $MARK_ID")
            RootChecker.execute("iptables -t mangle -A $CHAIN_DIVERT -j ACCEPT")
            // Handle TCP transparent socket
            RootChecker.execute("iptables -t mangle -I PREROUTING -p tcp -m socket -j $CHAIN_DIVERT")
            // Handle UDP transparent socket
            RootChecker.execute("iptables -t mangle -I PREROUTING -p udp -m socket -j $CHAIN_DIVERT")
        }

        return Pair(true, "")
    }

    /**
     * IPv6 TPROXY mode
     */
    private fun applyTProxyV6(proxyUid: Int): Boolean {
        val proxyUidStr = proxyUid.toString()

        // Setup IPv6 routing rules
        RootChecker.execute("ip -6 rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip -6 route del local default dev lo table $TABLE_ID 2>/dev/null")
        
        val (routeCode, routeOutput) = RootChecker.execute("ip -6 rule add fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF")
        if (routeCode != 0) {
            return false
        }
        
        val (routeCode2) = RootChecker.execute("ip -6 route add local default dev lo table $TABLE_ID")
        if (routeCode2 != 0) {
            return false
        }

        // Create CLASH_EXTERNAL_V6 chain (PREROUTING)
        if (!ensureChainV6("mangle", CHAIN_EXTERNAL_V6)) {
            return false
        }

        // Socket transparent connection handling
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -p tcp -m socket --transparent -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -p udp -m socket --transparent -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -m socket -j RETURN")

        // DNS skip rules
        if (useNatTableForDns) {
            RootChecker.execute("ip6tables -t mangle -I $CHAIN_EXTERNAL_V6 1 -p udp --dport 53 -j RETURN")
            RootChecker.execute("ip6tables -t mangle -I $CHAIN_EXTERNAL_V6 2 -p tcp --dport 53 -j RETURN")
        }

        // Create LOCAL_IP_V6 chain
        if (!ensureChainV6("mangle", CHAIN_LOCAL_IP_V6)) {
            return false
        }
        val localIpv6List = getLocalIpv6Addresses()
        for (ip in localIpv6List) {
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_IP_V6 -d $ip -j RETURN")
        }

        // Bypass IPv6 local addresses
        val bypassSubnetsV6 = listOf(
            "::1/128", "fe80::/10", "fc00::/7", "ff00::/8"
        )
        for (subnet in bypassSubnetsV6) {
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -d $subnet -j RETURN")
        }

        // Jump to LOCAL_IP_V6 chain
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -j $CHAIN_LOCAL_IP_V6")

        // TPROXY rules on lo interface
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -p tcp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -p udp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")

        // Hotspot interface TPROXY rules
        val hotspotIfaces = getHotspotInterfaces()
        for (iface in hotspotIfaces) {
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -p tcp -i $iface -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_EXTERNAL_V6 -p udp -i $iface -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
        }

        // Add to PREROUTING chain
        RootChecker.execute("ip6tables -t mangle -I PREROUTING -j $CHAIN_EXTERNAL_V6")

        // Create CLASH_LOCAL_V6 chain (OUTPUT)
        if (!ensureChainV6("mangle", CHAIN_LOCAL_V6)) {
            return false
        }

        // Skip proxy process itself
        RootChecker.execute("ip6tables -t mangle -I $CHAIN_LOCAL_V6 1 -m owner --uid-owner $proxyUidStr -j RETURN")

        // CONNMARK restore mark
        RootChecker.execute("ip6tables -t mangle -I $CHAIN_LOCAL_V6 2 -j CONNMARK --restore-mark")

        // Skip already marked connections
        RootChecker.execute("ip6tables -t mangle -I $CHAIN_LOCAL_V6 3 -m mark --mark $MARK_ID -j ACCEPT")

        // DNS skip rules
        if (useNatTableForDns) {
            RootChecker.execute("ip6tables -t mangle -I $CHAIN_LOCAL_V6 4 -p udp --dport 53 -j RETURN")
            RootChecker.execute("ip6tables -t mangle -I $CHAIN_LOCAL_V6 5 -p tcp --dport 53 -j RETURN")
        }

        // Skip LAN traffic
        for (subnet in bypassSubnetsV6) {
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_V6 -d $subnet -j RETURN")
        }

        // Handle local IP addresses
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_V6 -j $CHAIN_LOCAL_IP_V6")

        // Set mark (TCP and UDP)
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_V6 -p tcp -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_V6 -p udp -j MARK --set-xmark $MARK_ID")

        // CONNMARK save mark
        RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_V6 -j CONNMARK --save-mark")

        // Add to OUTPUT chain
        RootChecker.execute("ip6tables -t mangle -I OUTPUT -j $CHAIN_LOCAL_V6")

        // IPv6 DNS hijack
        applyDnsHijackV6(proxyUidStr)

        // Create DIVERT_V6 chain - handle transparent socket connections
        if (ensureChainV6("mangle", CHAIN_DIVERT_V6)) {
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_DIVERT_V6 -j MARK --set-xmark $MARK_ID")
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_DIVERT_V6 -j ACCEPT")
            // Handle TCP transparent socket
            RootChecker.execute("ip6tables -t mangle -I PREROUTING -p tcp -m socket -j $CHAIN_DIVERT_V6")
            // Handle UDP transparent socket
            RootChecker.execute("ip6tables -t mangle -I PREROUTING -p udp -m socket -j $CHAIN_DIVERT_V6")
        }

        return true
    }

    /**
     * IPv6 DNS hijack
     * Support both UDP and TCP DNS queries
     */
    private fun applyDnsHijackV6(proxyUidStr: String) {
        if (useNatTableForDns) {
            // CLASH_DNS_EXTERNAL_V6 (PREROUTING) - nat mode
            RootChecker.execute("ip6tables -t nat -F $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t nat -X $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t nat -N $CHAIN_DNS_EXTERNAL_V6")
            // UDP DNS hijack
            RootChecker.execute("ip6tables -t nat -A $CHAIN_DNS_EXTERNAL_V6 -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
            // TCP DNS hijack (for large DNS responses)
            RootChecker.execute("ip6tables -t nat -A $CHAIN_DNS_EXTERNAL_V6 -p tcp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
            RootChecker.execute("ip6tables -t nat -I PREROUTING -j $CHAIN_DNS_EXTERNAL_V6")

            // CLASH_DNS_LOCAL_V6 (OUTPUT) - nat mode
            RootChecker.execute("ip6tables -t nat -F $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t nat -X $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t nat -N $CHAIN_DNS_LOCAL_V6")
            if (proxyUidStr.isNotEmpty()) {
                RootChecker.execute("ip6tables -t nat -I $CHAIN_DNS_LOCAL_V6 1 -m owner --uid-owner $proxyUidStr -j RETURN")
            }
            // UDP DNS hijack
            RootChecker.execute("ip6tables -t nat -A $CHAIN_DNS_LOCAL_V6 -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
            // TCP DNS hijack (for large DNS responses)
            RootChecker.execute("ip6tables -t nat -A $CHAIN_DNS_LOCAL_V6 -p tcp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
            RootChecker.execute("ip6tables -t nat -I OUTPUT -j $CHAIN_DNS_LOCAL_V6")
        } else {
            // CLASH_DNS_EXTERNAL_V6 (PREROUTING) - mangle mode
            RootChecker.execute("ip6tables -t mangle -F $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t mangle -X $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t mangle -N $CHAIN_DNS_EXTERNAL_V6")
            // UDP DNS hijack
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_DNS_EXTERNAL_V6 -p udp --dport 53 -j TPROXY --tproxy-mark $MARK_ID --on-port $DNS_HIJACK_PORT")
            // TCP DNS hijack (for large DNS responses)
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_DNS_EXTERNAL_V6 -p tcp --dport 53 -j TPROXY --tproxy-mark $MARK_ID --on-port $DNS_HIJACK_PORT")
            RootChecker.execute("ip6tables -t mangle -I PREROUTING -j $CHAIN_DNS_EXTERNAL_V6")

            // CLASH_DNS_LOCAL_V6 (OUTPUT) - mangle mode
            RootChecker.execute("ip6tables -t mangle -F $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t mangle -X $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t mangle -N $CHAIN_DNS_LOCAL_V6")
            if (proxyUidStr.isNotEmpty()) {
                RootChecker.execute("ip6tables -t mangle -I $CHAIN_DNS_LOCAL_V6 1 -m owner --uid-owner $proxyUidStr -j RETURN")
            }
            // UDP DNS hijack
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_DNS_LOCAL_V6 -p udp --dport 53 -j MARK --set-xmark $MARK_ID")
            // TCP DNS hijack (for large DNS responses)
            RootChecker.execute("ip6tables -t mangle -A $CHAIN_DNS_LOCAL_V6 -p tcp --dport 53 -j MARK --set-xmark $MARK_ID")
            RootChecker.execute("ip6tables -t mangle -I OUTPUT -j $CHAIN_DNS_LOCAL_V6")
        }
    }

    /**
     * DNS hijack - nat table REDIRECT mode
     * Support both UDP and TCP DNS queries
     */
    private fun applyDnsHijackNatMode(proxyUidStr: String): Boolean {
        if (!ensureChain("nat", CHAIN_DNS_EXTERNAL)) {
            return false
        }
        // UDP DNS hijack
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_EXTERNAL -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        // TCP DNS hijack (for large DNS responses)
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_EXTERNAL -p tcp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        RootChecker.execute("iptables -t nat -I PREROUTING -j $CHAIN_DNS_EXTERNAL")

        if (!ensureChain("nat", CHAIN_DNS_LOCAL)) {
            return false
        }
        if (proxyUidStr.isNotEmpty()) {
            RootChecker.execute("iptables -t nat -I $CHAIN_DNS_LOCAL 1 -m owner --uid-owner $proxyUidStr -j RETURN")
        }
        // UDP DNS hijack
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_LOCAL -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        // TCP DNS hijack (for large DNS responses)
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_LOCAL -p tcp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        RootChecker.execute("iptables -t nat -I OUTPUT -j $CHAIN_DNS_LOCAL")

        return true
    }

    /**
     * DNS hijack - mangle table TPROXY mode
     * Support both UDP and TCP DNS queries
     */
    private fun applyDnsHijackMangleMode(proxyUidStr: String, dnsPort: Int): Boolean {
        if (!ensureChain("mangle", CHAIN_DNS_EXTERNAL)) {
            return false
        }
        // UDP DNS hijack
        RootChecker.execute("iptables -t mangle -A $CHAIN_DNS_EXTERNAL -p udp --dport 53 -j TPROXY --tproxy-mark $MARK_ID --on-port $dnsPort")
        // TCP DNS hijack (for large DNS responses)
        RootChecker.execute("iptables -t mangle -A $CHAIN_DNS_EXTERNAL -p tcp --dport 53 -j TPROXY --tproxy-mark $MARK_ID --on-port $dnsPort")
        RootChecker.execute("iptables -t mangle -I PREROUTING -j $CHAIN_DNS_EXTERNAL")

        if (!ensureChain("mangle", CHAIN_DNS_LOCAL)) {
            return false
        }
        if (proxyUidStr.isNotEmpty()) {
            RootChecker.execute("iptables -t mangle -I $CHAIN_DNS_LOCAL 1 -m owner --uid-owner $proxyUidStr -j RETURN")
        }
        // UDP DNS hijack
        RootChecker.execute("iptables -t mangle -A $CHAIN_DNS_LOCAL -p udp --dport 53 -j MARK --set-xmark $MARK_ID")
        // TCP DNS hijack (for large DNS responses)
        RootChecker.execute("iptables -t mangle -A $CHAIN_DNS_LOCAL -p tcp --dport 53 -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -I OUTPUT -j $CHAIN_DNS_LOCAL")

        return true
    }

    /**
     * REDIRECT mode - TCP only
     */
    private fun applyRedirect(proxyUid: Int): Pair<Boolean, String> {
        if (!ensureChain("nat", CHAIN_EXTERNAL)) {
            return Pair(false, "Create chain failed")
        }

        val redirectBypassSubnets = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.0.0.0/8", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16",
            "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32"
        )
        val hotspotSubnets = getHotspotSubnets()

        for (subnet in redirectBypassSubnets) {
            var shouldSkip = false
            for (hotspotSubnet in hotspotSubnets) {
                if (isSubnetContained(hotspotSubnet, subnet)) {
                    shouldSkip = true
                    break
                }
            }
            if (shouldSkip) {
                continue
            }
            val (code, output) = RootChecker.execute("iptables -t nat -A $CHAIN_EXTERNAL -d $subnet -j RETURN")
            if (code != 0) {
                clearTransparentProxy()
                return Pair(false, "REDIRECT bypass rule failed: $output")
            }
        }

        val (uidCode, uidOutput) = RootChecker.execute("iptables -t nat -A $CHAIN_EXTERNAL -m owner --uid-owner $proxyUid -j RETURN")
        if (uidCode != 0) {
            clearTransparentProxy()
            return Pair(false, "REDIRECT proxy self rule failed: $uidOutput")
        }

        val hotspotIfaces = getHotspotInterfaces()
        for (iface in hotspotIfaces) {
            RootChecker.execute("iptables -t nat -A $CHAIN_EXTERNAL -p tcp -i $iface -j REDIRECT --to-ports $TPROXY_PORT")
        }

        val (code, output) = RootChecker.execute("iptables -t nat -A $CHAIN_EXTERNAL -p tcp -j REDIRECT --to-ports $TPROXY_PORT")
        if (code != 0) {
            clearTransparentProxy()
            return Pair(false, "REDIRECT execution failed: $output")
        }

        val (code2, output2) = RootChecker.execute("iptables -t nat -I OUTPUT -j $CHAIN_EXTERNAL")
        if (code2 != 0) {
            clearTransparentProxy()
            return Pair(false, "REDIRECT add OUTPUT failed: $output2")
        }

        return Pair(true, "")
    }

    /**
     * Clear transparent proxy rules - complete cleanup
     */
    private fun clearTransparentProxy() {
        // Clean IPv4 routing rules
        RootChecker.execute("ip rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip route del local default dev lo table $TABLE_ID 2>/dev/null")

        // Clean IPv6 routing rules
        RootChecker.execute("ip -6 rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip -6 route del local default dev lo table $TABLE_ID 2>/dev/null")

        // Clean DIVERT chain
        RootChecker.execute("iptables -t mangle -D PREROUTING -p tcp -m socket -j $CHAIN_DIVERT 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_DIVERT 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_DIVERT 2>/dev/null")

        // Clean DIVERT_V6 chain
        RootChecker.execute("ip6tables -t mangle -D PREROUTING -p tcp -m socket -j $CHAIN_DIVERT_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -F $CHAIN_DIVERT_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -X $CHAIN_DIVERT_V6 2>/dev/null")

        // Clean DNS chains (mangle and nat tables)
        for (table in listOf("nat", "mangle")) {
            RootChecker.execute("iptables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_LOCAL 2>/dev/null")

            // IPv6 DNS chains
            RootChecker.execute("ip6tables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -F $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -X $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -F $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -X $CHAIN_DNS_LOCAL_V6 2>/dev/null")
        }

        // Clean BOX_LOCAL and BOX_EXTERNAL
        RootChecker.execute("iptables -t mangle -D PREROUTING -j $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -D OUTPUT -j $CHAIN_LOCAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_LOCAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_LOCAL 2>/dev/null")

        // Clean IPv6 chains
        RootChecker.execute("ip6tables -t mangle -D PREROUTING -j $CHAIN_EXTERNAL_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -D OUTPUT -j $CHAIN_LOCAL_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -F $CHAIN_EXTERNAL_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -X $CHAIN_EXTERNAL_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -F $CHAIN_LOCAL_V6 2>/dev/null")
        RootChecker.execute("ip6tables -t mangle -X $CHAIN_LOCAL_V6 2>/dev/null")

        // Clean nat table
        RootChecker.execute("iptables -t nat -D OUTPUT -j $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t nat -F $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t nat -X $CHAIN_EXTERNAL 2>/dev/null")

        // Clean LOCAL_IP chain
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
            return Pair(false, "Create lock chain failed")
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
                return Pair(false, "Lock background execution failed: $output")
            }
        }
        return Pair(true, "")
    }

    /**
     * Clear lock background rules
     */
    private fun clearLockBackground() {
        RootChecker.execute("iptables -t mangle -D OUTPUT -j $CHAIN_LOCK_BG 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_LOCK_BG 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_LOCK_BG 2>/dev/null")
    }

    /**
     * Apply DNS hijack rules (independent call)
     */
    suspend fun applyDnsHijack(): Pair<Boolean, String> {
        clearDnsHijack()

        val proxyUid = getAppUid()
        val proxyUidStr = if (proxyUid > 0) proxyUid.toString() else ""

        if (useNatTableForDns) {
            if (!applyDnsHijackNatMode(proxyUidStr)) {
                return Pair(false, "Create DNS hijack chain failed")
            }
        } else {
            if (!applyDnsHijackMangleMode(proxyUidStr, DNS_HIJACK_PORT)) {
                return Pair(false, "Create DNS hijack chain failed")
            }
        }

        return Pair(true, "")
    }

    /**
     * Clear DNS hijack rules
     */
    private fun clearDnsHijack() {
        for (table in listOf("nat", "mangle")) {
            RootChecker.execute("iptables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_LOCAL 2>/dev/null")

            // IPv6 DNS chains
            RootChecker.execute("ip6tables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -F $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -X $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -F $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            RootChecker.execute("ip6tables -t $table -X $CHAIN_DNS_LOCAL_V6 2>/dev/null")
        }
    }

    /**
     * Clear transparent proxy rules individually
     */
    suspend fun clearTransparentProxyRules() {
        clearTransparentProxy()
    }

    /**
     * Clear lock background rules individually
     */
    suspend fun clearLockBackgroundRules() {
        clearLockBackground()
    }

    /**
     * Clear DNS hijack rules individually
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