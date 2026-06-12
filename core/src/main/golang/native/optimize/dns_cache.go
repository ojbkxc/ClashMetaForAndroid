package optimize

import (
	"sync"
	"time"
)

// dnsCache caches DNS resolution results to reduce per-connection lookup latency.
// Proxy server domains rarely change; caching avoids 50-200ms DNS round-trips.
type dnsCache struct {
	mu       sync.RWMutex
	ttl      time.Duration
	storeMap map[string]*dnsCacheEntry
}

type dnsCacheEntry struct {
	expiresAt time.Time
	ip        string
}

func newDNSCache(ttlSeconds int) *dnsCache {
	return &dnsCache{
		ttl:      time.Duration(ttlSeconds) * time.Second,
		storeMap: make(map[string]*dnsCacheEntry, 64),
	}
}

func (c *dnsCache) lookup(host string) (string, bool) {
	c.mu.RLock()
	entry, ok := c.storeMap[host]
	c.mu.RUnlock()

	if !ok || time.Now().After(entry.expiresAt) {
		if ok {
			c.mu.Lock()
			delete(c.storeMap, host)
			c.mu.Unlock()
		}
		return "", false
	}
	return entry.ip, true
}

func (c *dnsCache) store(host, ip string) {
	c.mu.Lock()
	c.storeMap[host] = &dnsCacheEntry{
		expiresAt: time.Now().Add(c.ttl),
		ip:        ip,
	}
	c.mu.Unlock()
}

func (c *dnsCache) clear() {
	c.mu.Lock()
	c.storeMap = make(map[string]*dnsCacheEntry, 64)
	c.mu.Unlock()
}

func (c *dnsCache) size() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.storeMap)
}

func (c *dnsCache) cleanup() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	for host, entry := range c.storeMap {
		if now.After(entry.expiresAt) {
			delete(c.storeMap, host)
		}
	}
}