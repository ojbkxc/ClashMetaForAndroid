package optimize

import (
	"net"
	"syscall"

	"github.com/metacubex/mihomo/component/dialer"
)

// SetupSocketHook chains socket-level optimizations into the dialer's
// DefaultSocketHook while preserving the existing hook (CMFA socket protection).
// Must be called before any connections are dialed.
func SetupSocketHook() {
	original := dialer.DefaultSocketHook
	dialer.DefaultSocketHook = func(network, address string, c syscall.RawConn) error {
		if original != nil {
			if err := original(network, address, c); err != nil {
				return err
			}
		}
		return applySocketOpts(network, c)
	}
}

// SetupDNSCache installs a caching DNS resolver that intercepts
// [resolver.DefaultResolver] to reduce repeated DNS lookups.
// Proxy server addresses are typically fixed domains; caching avoids
// 50-200ms per-connection DNS delay.
func SetupDNSCache(ttlSeconds int) {
	if ttlSeconds <= 0 {
		ttlSeconds = 300 // default 5 minutes
	}
	cache := newDNSCache(ttlSeconds)

	o, err := GetGlobalOptimizer()
	if err != nil {
		return
	}
	o.SetDNSCache(cache)
}

// GetCachedIP returns a cached IP for the given host, or empty string.
func GetCachedIP(host string) (string, bool) {
	o, err := GetGlobalOptimizer()
	if err != nil {
		return "", false
	}
	cache := o.GetDNSCache()
	if cache == nil {
		return "", false
	}
	return cache.lookup(host)
}

// resolveAndCache performs a DNS lookup and stores the result.
func resolveAndCache(host string) (string, error) {
	o, err := GetGlobalOptimizer()
	if err != nil {
		return "", err
	}

	cache := o.GetDNSCache()
	if cache != nil {
		ip, ok := cache.lookup(host)
		if ok {
			return ip, nil
		}
	}

	// Fall back to standard resolution
	ips, err := net.LookupHost(host)
	if err != nil || len(ips) == 0 {
		return "", err
	}

	ip := ips[0]
	if cache != nil {
		cache.store(host, ip)
	}
	return ip, nil
}