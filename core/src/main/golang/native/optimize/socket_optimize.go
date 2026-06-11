package optimize

import (
	"syscall"
)

// UDP socket buffer sizes tuned for QUIC-based protocols (Hysteria2, TUIC, VLESS H3).
// Read buffer is larger because QUIC receive windows can be much larger than send windows.
const (
	udpRcvBufSize = 2097152  // 2MB — accommodate large QUIC receive windows
	udpSndBufSize = 1048576  // 1MB — sufficient for most QUIC send paths
)

// Linux/Android socket options not in Go's syscall package.
const (
	_IPPROTO_IPV6        = 41  // syscall.IPPROTO_IPV6
	_IPV6_MTU_DISCOVER   = 23  // not exposed in syscall
	_IPV6_PMTUDISC_DO    = 2   // same as IP_PMTUDISC_DO
	_SO_REUSEPORT        = 15  // syscall.SO_REUSEPORT
	_IP_TOS              = 1   // syscall.IP_TOS
	_IPV6_TCLASS         = 67  // not exposed in syscall
	_IPTOS_LOWDELAY      = 0x10 // minimized delay
	_TCP_FASTOPEN_CONNECT = 30 // syscall.TCP_FASTOPEN_CONNECT (Linux 4.11+)
)

// applySocketOpts configures per-connection socket options.
//
// UDP (QUIC / Hysteria2 / TUIC / VLESS H3):
//   - Large RCVBUF (2MB) and SNDBUF (1MB) for flow control throughput
//   - PMTUD for both IPv4 and IPv6 (kernel-level Path MTU Discovery)
//   - SO_REUSEPORT for multipath/bonding scenarios
//   - IP_TOS LOWDELAY for QoS prioritization on supported networks
//
// TCP (Trojan / VMess / Shadowsocks / VLESS TCP):
//   - TCP_NODELAY to disable Nagle's algorithm
//   - TCP_FASTOPEN_CONNECT for reduced handshake latency (Linux 4.11+)
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
				_ = syscall.SetsockoptInt(fdInt, _IPPROTO_IPV6, _IPV6_MTU_DISCOVER, _IPV6_PMTUDISC_DO)
			}

			// --- SO_REUSEPORT: allow multiple sockets on same port (multipath) ---
			_ = syscall.SetsockoptInt(fdInt, syscall.SOL_SOCKET, _SO_REUSEPORT, 1)

			// --- QoS: low-latency DSCP marking ---
			switch network {
			case "udp4", "udp":
				_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_IP, _IP_TOS, _IPTOS_LOWDELAY)
			case "udp6":
				_ = syscall.SetsockoptInt(fdInt, _IPPROTO_IPV6, _IPV6_TCLASS, _IPTOS_LOWDELAY)
			}

		case isTCP(network):
			// Disable Nagle's algorithm for lower latency
			_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_TCP, syscall.TCP_NODELAY, 1)

			// TCP Fast Open: reduces 1 RTT from handshake (Linux 4.11+)
			// Non-fatal if kernel doesn't support it — silently ignored.
			_ = syscall.SetsockoptInt(fdInt, syscall.IPPROTO_TCP, _TCP_FASTOPEN_CONNECT, 1)
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