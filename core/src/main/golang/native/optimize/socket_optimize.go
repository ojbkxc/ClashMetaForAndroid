package optimize

import (
	"syscall"
)

// UDP socket buffer sizes tuned for QUIC-based protocols (Hysteria2, TUIC, VLESS H3).
// Read buffer is larger because QUIC receive windows can be much larger than send windows.
const (
	udpRcvBufSize = 8388608  // 8MB — covers 5G/WiFi6 + Hysteria2 high throughput
	udpSndBufSize = 1048576  // 1MB — sufficient for most QUIC send paths
)

// Linux/Android socket options not in Go's syscall package.
// These constants are stable across all Linux kernel versions since 2.6.x
// and are safe to use on Android (which runs Linux 3.x+).
const (
	_IPV6_MTU_DISCOVER = 23   // not exposed in syscall; Linux 2.6.17+
	_IPV6_PMTUDISC_DO  = 2    // same as IP_PMTUDISC_DO
	_IPV6_TCLASS       = 67   // not exposed in syscall; Linux 2.6+
	_IPTOS_LOWDELAY    = 0x10 // minimized delay; RFC 1349
	_TCP_QUICKACK      = 12   // TCP_QUICKACK; Linux 2.4.4+
)

// applySocketOpts configures per-connection socket options.
//
// UDP (QUIC / Hysteria2 / TUIC / VLESS H3):
//   - Large RCVBUF (2MB) and SNDBUF (1MB) for flow control throughput
//   - PMTUD for both IPv4 and IPv6 (kernel-level Path MTU Discovery)
//   - IP_TOS LOWDELAY for QoS prioritization on supported networks
//
// TCP (Trojan / VMess / Shadowsocks / VLESS TCP):
//   - TCP_NODELAY to disable Nagle's algorithm
func applySocketOpts(network string, c syscall.RawConn) error {
	var opErr error
	ctrlErr := c.Control(func(fd uintptr) {
		fdInt := int(fd)

		switch {
		case isUDP(network):
			// --- Buffer sizing ---
			// Read buffer: larger for ingress QUIC streams
			_ = syscall.SetsockoptInt(fdInt, syscall.SOL_SOCKET, syscall.SO_RCVBUF, udpRcvBufSize)
			// Send buffer: smaller for egress
			_ = syscall.SetsockoptInt(fdInt, syscall.SOL_SOCKET, syscall.SO_SNDBUF, udpSndBufSize)

			// --- PMTUD: kernel-level Path MTU Discovery ---
			// QUIC relies on accurate MTU; PMTUD prevents fragmentation.
			switch network {
			case "udp4", "udp":
				_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_IP, syscall.IP_MTU_DISCOVER, syscall.IP_PMTUDISC_DO)
			case "udp6":
				_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_IPV6, _IPV6_MTU_DISCOVER, _IPV6_PMTUDISC_DO)
			}

			// --- QoS: low-latency DSCP marking ---
			switch network {
			case "udp4", "udp":
				_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_IP, syscall.IP_TOS, _IPTOS_LOWDELAY)
			case "udp6":
				_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_IPV6, _IPV6_TCLASS, _IPTOS_LOWDELAY)
			}

		case isTCP(network):
			// Disable Nagle's algorithm for lower latency
			_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_TCP, syscall.TCP_NODELAY, 1)
			
			// TCP_QUICKACK: Enable quick ACK responses to reduce latency for small packets
			_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_TCP, _TCP_QUICKACK, 1)
			
			// SO_KEEPALIVE: Keep connection alive to detect dead connections early
			// Important for Reality connections to avoid being dropped by firewalls
			_ = syscall.SetsockoptInt(fdInt, syscall.SOL_SOCKET, syscall.SO_KEEPALIVE, 1)
			
			// SO_RCVBUF/SO_SNDBUF: Increase buffer sizes for high-latency networks
			// 256KB receive buffer and 128KB send buffer
			_ = syscall.SetsockoptInt(fdInt, syscall.SOL_SOCKET, syscall.SO_RCVBUF, 262144)
			_ = syscall.SetsockoptInt(fdInt, syscall.SOL_SOCKET, syscall.SO_SNDBUF, 131072)
			
			// QoS: low-latency DSCP marking for better network prioritization
			switch network {
			case "tcp4", "tcp":
				_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_IP, syscall.IP_TOS, _IPTOS_LOWDELAY)
			case "tcp6":
				_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_IPV6, _IPV6_TCLASS, _IPTOS_LOWDELAY)
			}
		}
	})
	if ctrlErr != nil {
		return ctrlErr
	}
	return opErr
}

func isUDP(network string) bool {
	return network == "udp" || network == "udp4" || network == "udp6"
}

func isTCP(network string) bool {
	return network == "tcp" || network == "tcp4" || network == "tcp6"
}