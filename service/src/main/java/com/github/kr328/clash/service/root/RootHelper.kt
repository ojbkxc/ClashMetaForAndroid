package com.github.kr328.clash.service.root

import com.github.kr328.clash.common.RootChecker

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

    // Routing mark and table ID (reference Surfing)
    private const val MARK_ID = "0x1/0x1"
    private const val MARK_VALUE = "0x1"
    private const val TABLE_ID = "100"
    private const val TABLE_PREF = "100"

    // DNS hijack mode: true = use nat table REDIRECT, false = use mangle table TPROXY
    private var useNatTableForDns = true

    // Cached dynamically obtained UID
    private var cachedProxyUid: Int = -1

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
     * Hotspot interface data also needs to go through TPROXY proxy
     *
     * Supported interface types:
     * - wlan+ : wlan0, wlan1, wlan1-usb etc.
     * - ap+   : ap0, ap1 etc.
     * - rndis+: rndis0 etc. (USB tethering)
     * - ncm+  : ncm0 etc. (USB tethering)
     * - eth+  : eth0 etc. (Ethernet)
     * - p2p+  : p2p0 etc. (WiFi Direct)
     */
    private fun getHotspotInterfaces(): List<String> {
        val hotspotIfaces = mutableSetOf<String>()

        // Get all network interfaces
        val (code, output) = RootChecker.execute("ip link show | cut -d: -f2 | tr -d ' '")
        if (code != 0 || output.isEmpty()) return emptyList()

        val allInterfaces = output.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Wildcard patterns (refer to Surfing's ap_list)
        val wildcardPatterns = listOf("wlan", "ap", "rndis", "ncm", "eth", "p2p")

        for (iface in allInterfaces) {
            for (pattern in wildcardPatterns) {
                // Match: wlan0, ap1, rndis0, ncm0, eth0, p2p0 etc.
                if (iface.startsWith(pattern) && iface.length > pattern.length) {
                    val suffix = iface.substring(pattern.length)
                    // Ensure the following characters are digits or special characters (not letters forming a word)
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
     * Hotspot subnets should not be bypassed, need to be proxied
     */
    private fun getHotspotSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        val hotspotIfaces = getHotspotInterfaces()

        for (iface in hotspotIfaces) {
            // Get interface IPv4 address
            val (code, output) = RootChecker.execute("ip -4 addr show $iface | grep inet | awk '{print \$2}'")
            if (code == 0 && output.isNotEmpty()) {
                output.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { ip ->
                    if (!ip.contains(":")) { // Exclude IPv6
                        subnets.add(ip)
                    }
                }
            }
        }

        return subnets
    }

    /**
     * Check if a subnet is contained within a parent subnet
     * e.g., 192.168.43.0/24 is contained in 192.168.0.0/16
     */
    private fun isSubnetContained(subnet: String, parentSubnet: String): Boolean {
        try {
            // Parse subnet format "IP/prefix length"
            val (subnetIp, subnetMask) = subnet.split("/")
            val (parentIp, parentMask) = parentSubnet.split("/")

            val subnetPrefix = subnetMask.toIntOrNull() ?: return false
            val parentPrefix = parentMask.toIntOrNull() ?: return false

            // If the parent prefix is shorter (larger), the parent subnet is wider
            if (parentPrefix >= subnetPrefix) {
                return false
            }

            // Convert IPs to integers
            val subnetIpInt = ipToInt(subnetIp)
            val parentIpInt = ipToInt(parentIp)

            // Calculate subnet masks
            val subnetMaskInt = if (subnetPrefix == 0) 0 else (0xFFFFFFFF shl (32 - subnetPrefix))
            val parentMaskInt = if (parentPrefix == 0) 0 else (0xFFFFFFFF shl (32 - parentPrefix))

            // Check if subnet is within parent network
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
     * Optimize kernel parameters (refer to Surfing)
     */
    private fun optimizeKernel(): Boolean {
        val commands = listOf(
            // UDP conntrack timeout optimization
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout=30 2>/dev/null",
            "sysctl -w net.netfilter.nf_conntrack_udp_timeout_stream=15 2>/dev/null",
            // Write directly to proc files (some devices have sysctl disabled)
            "echo 30 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout 2>/dev/null",
            "echo 15 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout_stream 2>/dev/null",
            // TCP conntrack optimization
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
     * Complete TPROXY solution referenced from Surfing
     */
    suspend fun applyTransparentProxy(): Pair<Boolean, String> {
        clearTransparentProxy()

        val proxyUid = getAppUid()
        if (proxyUid < 0) {
            return Pair(false, "Cannot get app UID")
        }

        optimizeKernel()

        if (isTProxySupported()) {
            val result = applyTProxy(proxyUid)
            if (result.first) {
                return Pair(true, "TPROXY mode (TCP+UDP supported)")
            }
            clearTransparentProxy()
        }

        val result = applyRedirect(proxyUid)
        if (result.first) {
            return Pair(true, "REDIRECT mode (TCP only)")
        }
        return result
    }

    /**
     * TPROXY mode - reference Surfing implementation
     */
    private fun applyTProxy(proxyUid: Int): Pair<Boolean, String> {
        val proxyUidStr = proxyUid.toString()

        // ========== 1. Setup routing rules (IPv4) ==========
        RootChecker.execute("ip rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip route del local default dev lo table $TABLE_ID 2>/dev/null")
        var (code, output) = RootChecker.execute("ip rule add fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF")
        if (code != 0) return Pair(false, "Setup routing rules failed: $output")
        val (code2, output2) = RootChecker.execute("ip route add local default dev lo table $TABLE_ID")
        if (code2 != 0) return Pair(false, "Setup local routing table failed: $output2")

        // ========== 2. Create CLASH_EXTERNAL chain (PREROUTING) ==========
        // Reference Surfing: handle socket transparent connection first, then bypass LAN
        if (!ensureChain("mangle", CHAIN_EXTERNAL)) {
            return Pair(false, "Create CLASH_EXTERNAL chain failed")
        }

        // Socket transparent connection handling (reference Surfing --transparent flag)
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p tcp -m socket --transparent -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p udp -m socket --transparent -j MARK --set-xmark $MARK_ID")
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -m socket -j RETURN")

        // DNS skip rules (only when use_nat_table=true, reference Surfing)
        if (useNatTableForDns) {
            RootChecker.execute("iptables -t mangle -I $CHAIN_EXTERNAL 1 -p udp --dport 53 -j RETURN")
            RootChecker.execute("iptables -t mangle -I $CHAIN_EXTERNAL 2 -p tcp --dport 53 -j RETURN")
        }

        // Get hotspot subnets (these subnets should not be bypassed, need to be proxied)
        val hotspotSubnets = getHotspotSubnets()

        // Create LOCAL_IP chain (IPv4 local addresses)
        if (!ensureChain("mangle", CHAIN_LOCAL_IP)) {
            return Pair(false, "Create LOCAL_IP chain failed")
        }
        val localIpv4List = getLocalIpv4Addresses()
        for (ip in localIpv4List) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_LOCAL_IP -d $ip -j RETURN")
        }

        // Bypass LAN traffic (reference Surfing - before LOCAL_IP)
        // But need to exclude hotspot subnets, otherwise hotspot client traffic will be bypassed
        val bypassSubnets = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.0.0.0/8", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16",
            "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32"
        )
        for (subnet in bypassSubnets) {
            // Check if need to exclude hotspot subnet
            // Hotspot subnet (e.g. 192.168.43.0/24) should not be bypassed
            var shouldSkip = false
            for (hotspotSubnet in hotspotSubnets) {
                // If hotspot subnet is contained in this large subnet, skip
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

        // TPROXY rules - set on lo interface (reference Surfing)
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p tcp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
        RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p udp -i lo -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")

        // Hotspot/network sharing interface TPROXY rules (reference Surfing ap_list)
        // Hotspot interface traffic should be handled by TPROXY, not bypassed
        val hotspotIfaces = getHotspotInterfaces()
        for (iface in hotspotIfaces) {
            RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p tcp -i $iface -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
            RootChecker.execute("iptables -t mangle -A $CHAIN_EXTERNAL -p udp -i $iface -j TPROXY --tproxy-mark $MARK_ID --on-port $TPROXY_PORT")
        }

        // Add to PREROUTING chain
        RootChecker.execute("iptables -t mangle -I PREROUTING -j $CHAIN_EXTERNAL")

        // ========== 3. Create CLASH_LOCAL chain (OUTPUT) ==========
        // Reference Surfing rule order:
        // 1. Skip proxy itself
        // 2. CONNMARK restore
        // 3. Skip marked connections
        // 4-5. DNS skip (only use_nat_table=true)
        // 6+. LAN bypass + LOCAL_IP
        // n. Set mark
        // n+1. CONNMARK save
        if (!ensureChain("mangle", CHAIN_LOCAL)) {
            return Pair(false, "Create BOX_LOCAL chain failed")
        }

        // Position 1: Skip proxy process itself
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 1 -m owner --uid-owner $proxyUidStr -j RETURN")

        // Position 2: CONNMARK restore mark
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 2 -j CONNMARK --restore-mark")

        // Position 3: Skip already marked connections
        RootChecker.execute("iptables -t mangle -I $CHAIN_LOCAL 3 -m mark --mark $MARK_ID -j ACCEPT")

        // Position 4-5: DNS skip rules (only when use_nat_table=true, reference Surfing)
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

        // ========== 4. DNS hijack (reference Surfing) ==========
        if (useNatTableForDns) {
            if (!applyDnsHijackNatMode(proxyUidStr)) {
                return Pair(false, "DNS hijack failed")
            }
        } else {
            if (!applyDnsHijackMangleMode(proxyUidStr, TPROXY_PORT)) {
                return Pair(false, "DNS hijack failed")
            }
        }

        // ========== 5. Create DIVERT chain to accelerate established connections ==========
        if (!ensureChain("mangle", CHAIN_DIVERT)) {
            // DIVERT is not essential, continue
        } else {
            RootChecker.execute("iptables -t mangle -A $CHAIN_DIVERT -j MARK --set-xmark $MARK_ID")
            RootChecker.execute("iptables -t mangle -A $CHAIN_DIVERT -j ACCEPT")
            RootChecker.execute("iptables -t mangle -I PREROUTING -p tcp -m socket -j $CHAIN_DIVERT")
        }

        // ========== 6. Create CLASH_LOCAL_IP_V6 chain (IPv6 local addresses) ==========
        if (!ensureChain("mangle", CHAIN_LOCAL_IP_V6)) {
            // IPv6 may not be supported, continue
        } else {
            val localIpv6List = getLocalIpv6Addresses()
            for (ip in localIpv6List) {
                RootChecker.execute("ip6tables -t mangle -A $CHAIN_LOCAL_IP_V6 -d $ip -j RETURN 2>/dev/null")
            }
        }

        return Pair(true, "")
    }

    /**
     * DNS hijack - nat table REDIRECT mode (reference Surfing)
     */
    private fun applyDnsHijackNatMode(proxyUidStr: String): Boolean {
        // CLASH_DNS_EXTERNAL (PREROUTING)
        if (!ensureChain("nat", CHAIN_DNS_EXTERNAL)) {
            return false
        }
        RootChecker.execute("iptables -t nat -A $CHAIN_DNS_EXTERNAL -p udp --dport 53 -j REDIRECT --to-ports $DNS_HIJACK_PORT")
        RootChecker.execute("iptables -t nat -I PREROUTING -j $CHAIN_DNS_EXTERNAL")

        // CLASH_DNS_LOCAL (OUTPUT)
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
     * DNS hijack - mangle table TPROXY mode (reference Surfing)
     * Used when use_nat_table=false
     */
    private fun applyDnsHijackMangleMode(proxyUidStr: String, dnsPort: Int): Boolean {
        // CLASH_DNS_EXTERNAL (PREROUTING)
        if (!ensureChain("mangle", CHAIN_DNS_EXTERNAL)) {
            return false
        }
        RootChecker.execute("iptables -t mangle -A $CHAIN_DNS_EXTERNAL -p udp --dport 53 -j TPROXY --tproxy-mark $MARK_ID --on-port $dnsPort")
        RootChecker.execute("iptables -t mangle -I PREROUTING -j $CHAIN_DNS_EXTERNAL")

        // CLASH_DNS_LOCAL (OUTPUT)
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
     * REDIRECT mode - TCP only (reference Surfing)
     * Need to handle hotspot interfaces
     */
    private fun applyRedirect(proxyUid: Int): Pair<Boolean, String> {
        if (!ensureChain("nat", CHAIN_EXTERNAL)) {
            return Pair(false, "Create chain failed")
        }

        // Basic rules (refer to Surfing's intranet list)
        // Note: REDIRECT mode also needs to exclude hotspot subnets
        val redirectBypassSubnets = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.0.0.0/8", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16",
            "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32"
        )
        // Get hotspot subnets (these should not be bypassed)
        val hotspotSubnets = getHotspotSubnets()

        for (subnet in redirectBypassSubnets) {
            // Check if need to exclude hotspot subnet
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

        // Proxy self
        val (uidCode, uidOutput) = RootChecker.execute("iptables -t nat -A $CHAIN_EXTERNAL -m owner --uid-owner $proxyUid -j RETURN")
        if (uidCode != 0) {
            clearTransparentProxy()
            return Pair(false, "REDIRECT proxy self rule failed: $uidOutput")
        }

        // Hotspot interface TCP redirect (refer to Surfing)
        val hotspotIfaces = getHotspotInterfaces()
        for (iface in hotspotIfaces) {
            val (code, output) = RootChecker.execute("iptables -t nat -A $CHAIN_EXTERNAL -p tcp -i $iface -j REDIRECT --to-ports $TPROXY_PORT")
            if (code != 0) {
                // continue with other interfaces
            }
        }

        // Main TCP redirect rule
        val (code, output) = RootChecker.execute("iptables -t nat -A $CHAIN_EXTERNAL -p tcp -j REDIRECT --to-ports $TPROXY_PORT")
        if (code != 0) {
            clearTransparentProxy()
            return Pair(false, "REDIRECT execution failed: $output")
        }

        // Add to OUTPUT chain
        val (code2, output2) = RootChecker.execute("iptables -t nat -I OUTPUT -j $CHAIN_EXTERNAL")
        if (code2 != 0) {
            clearTransparentProxy()
            return Pair(false, "REDIRECT add OUTPUT failed: $output2")
        }

        return Pair(true, "")
    }

    /**
     * Clear transparent proxy rules - complete cleanup (reference Surfing stop_tproxy)
     */
    private fun clearTransparentProxy() {
        // Clean IPv4 routing rules
        RootChecker.execute("ip rule del fwmark $MARK_VALUE table $TABLE_ID pref $TABLE_PREF 2>/dev/null")
        RootChecker.execute("ip route del local default dev lo table $TABLE_ID 2>/dev/null")

        // Clean DIVERT chain
        RootChecker.execute("iptables -t mangle -D PREROUTING -p tcp -m socket -j $CHAIN_DIVERT 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_DIVERT 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_DIVERT 2>/dev/null")

        // Clean DNS chains (mangle and nat tables)
        for (table in listOf("nat", "mangle")) {
            RootChecker.execute("iptables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_EXTERNAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -F $CHAIN_DNS_LOCAL 2>/dev/null")
            RootChecker.execute("iptables -t $table -X $CHAIN_DNS_LOCAL 2>/dev/null")
        }

        // Clean BOX_LOCAL and BOX_EXTERNAL
        RootChecker.execute("iptables -t mangle -D PREROUTING -j $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -D OUTPUT -j $CHAIN_LOCAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_EXTERNAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -F $CHAIN_LOCAL 2>/dev/null")
        RootChecker.execute("iptables -t mangle -X $CHAIN_LOCAL 2>/dev/null")

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