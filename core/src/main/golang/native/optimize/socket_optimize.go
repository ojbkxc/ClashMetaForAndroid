package optimize

import (
	"net"
	"syscall"
)

// socketBufferSizes defines optimal UDP socket buffer sizes for QUIC performance.
// QUIC over UDP benefits from large buffers to handle flow control windows
// and reduce packet loss under load.
const (
	udpBufSize = 1048576 // 1MB for QUIC (Hysteria2, TUIC, VLESS H3)
)

// SetupSocketHook chains our socket optimizations after the existing
// DefaultSocketHook (typically CMFA socket protection). This is called
// synchronously in coreInit() before any connections are created.
func SetupSocketHook() {
	importDialer()
	original := dialerRef.DefaultSocketHook
	dialerRef.DefaultSocketHook = func(network, address string, c syscall.RawConn) error {
		// Call original hook first (CMFA socket protection)
		if original != nil {
			if err := original(network, address, c); err != nil {
				return err
			}
		}
		// Apply our socket-level optimizations
		return applySocketOpts(network, c)
	}
}

// applySocketOpts configures per-connection socket options:
//   - UDP: large RCVBUF/SNDBUF for QUIC throughput, IP_MTU_DISCOVER for PMTUD
//   - TCP: TCP_NODELAY to disable Nagle's algorithm
func applySocketOpts(network string, c syscall.RawConn) error {
	var opErr error
	ctrlErr := c.Control(func(fd uintptr) {
		switch {
		case isUDP(network):
			// Large buffer for QUIC flow control
			if err := syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF, udpBufSize); err != nil {
				opErr = err
				return
			}
			if err := syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_SNDBUF, udpBufSize); err != nil {
				opErr = err
				return
			}
			// Enable kernel-level Path MTU Discovery (sets DF flag on UDP)
			// QUIC's built-in PMTUD relies on this for accurate MTU detection
			_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_MTU_DISCOVER, syscall.IP_PMTUDISC_DO)

		case isTCP(network):
			// Disable Nagle's algorithm for lower latency
			// TCP_NODELAY = 1 means send data immediately without buffering
			if err := syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, syscall.TCP_NODELAY, 1); err != nil {
				opErr = err
				return
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